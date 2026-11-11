package cn.edu.whu.irlab.systemdirectionclassifier.manager.model;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.util.*;

final class ModelConnectionConfig {
    final String protocol, provider, type, baseUrl, model, auth;
    final JsonNode adapter, routing;
    final List<String> endpoints;

    private ModelConnectionConfig(String protocol, String provider, String type, String baseUrl, String model,
                                  String auth, JsonNode adapter, JsonNode routing) {
        this.protocol = protocol;
        this.provider = provider;
        this.type = type;
        this.baseUrl = baseUrl;
        this.model = model;
        this.auth = auth;
        this.adapter = adapter;
        this.routing = routing;
        endpoints = new ArrayList<>();
        endpoints.add(baseUrl);
        if (routing != null) {
            for (JsonNode endpoint : routing.path("additional_endpoints")) endpoints.add(endpoint.path("base_url").asText());
        }
    }

    static ModelConnectionConfig input(JsonNode body, ObjectMapper json) {
        String protocol = choice(body, "protocolCode", "openai_compatible");
        String provider = choice(body, "providerCode", "generic", "dashscope", "vllm");
        String type = choice(body, "modelType", "chat", "embedding");
        String auth = choice(body, "authType", "bearer", "none");
        String model = text(body, "modelId", 255, true);
        if (!model.matches("[A-Za-z0-9_][A-Za-z0-9_.:/-]*") || model.startsWith("sk-")) {
            throw invalid("模型 ID 格式无效");
        }
        String baseUrl = url(text(body, "baseUrl", 2048, true));
        JsonNode thinking = body.path("enableThinking");
        ObjectNode adapter = null;
        if (!thinking.isMissingNode() && !thinking.isNull()) {
            if (!thinking.isBoolean() || !"chat".equals(type) || "generic".equals(provider)) {
                throw invalid("当前模型服务不支持此思考模式配置");
            }
            adapter = json.createObjectNode().put("enable_thinking", thinking.booleanValue());
        }
        JsonNode endpoints = body.path("additionalEndpoints");
        ObjectNode routing = null;
        if (!endpoints.isMissingNode() && !endpoints.isNull()) {
            if (!endpoints.isArray() || endpoints.size() > 4) throw invalid("最多配置四个附加地址");
            if (!endpoints.isEmpty()) {
                routing = json.createObjectNode().put("strategy", "weighted_round_robin");
                ArrayNode rows = routing.putArray("additional_endpoints");
                Set<String> seen = new HashSet<>(Collections.singleton(baseUrl));
                for (JsonNode endpoint : endpoints) {
                    String address = url(text(endpoint, "baseUrl", 2048, true));
                    JsonNode weight = endpoint.path("weight");
                    if (!seen.add(address)) throw invalid("服务地址不能重复");
                    if (!weight.isIntegralNumber() || !weight.canConvertToInt() || weight.intValue() < 1 || weight.intValue() > 100) {
                        throw invalid("附加地址权重必须为 1 至 100 的整数");
                    }
                    rows.addObject().put("base_url", address).put("weight", weight.intValue());
                }
            }
        }
        return new ModelConnectionConfig(protocol, provider, type, baseUrl, model, auth, adapter, routing);
    }

    static ModelConnectionConfig stored(Map<String,Object> row, ObjectMapper json) {
        return new ModelConnectionConfig((String)row.get("protocolCode"), (String)row.get("providerCode"),
            (String)row.get("modelType"), (String)row.get("baseUrl"), (String)row.get("modelId"),
            (String)row.get("authType"), storedJson(json, row.get("adapterOptionsJson")),
            storedJson(json, row.get("routingOptionsJson")));
    }

    private static JsonNode storedJson(ObjectMapper json, Object value) {
        if (value == null) return null;
        try {
            return json.readTree(value.toString());
        } catch (IOException e) {
            throw new IdentityException(409, "保存的模型配置无法读取");
        }
    }

    boolean same(ModelConnectionConfig other) {
        return protocol.equals(other.protocol) && Objects.equals(provider, other.provider) && type.equals(other.type)
            && baseUrl.equals(other.baseUrl) && model.equals(other.model) && auth.equals(other.auth)
            && Objects.equals(adapter, other.adapter) && Objects.equals(routing, other.routing);
    }

    byte[] scope(ObjectMapper json) {
        // Keep the single-address format compatible with the existing Python sample importer.
        List<String> values = new ArrayList<>(Arrays.asList("v1", protocol, provider, auth, baseUrl));
        List<String> additional = new ArrayList<>(endpoints.subList(1, endpoints.size()));
        Collections.sort(additional);
        values.addAll(additional);
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(values));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build model credential scope");
        }
    }

    static String url(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            String path = uri.getRawPath();
            int port = uri.getPort();
            if ((!scheme.equals("https") && !scheme.equals("http")) || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || port == 0 || port > 65535
                || path == null || !path.matches("[A-Za-z0-9_./~-]*") || path.contains("//")
                || !uri.normalize().getRawPath().equals(path)) {
                throw new IllegalArgumentException();
            }
            path = path.replaceFirst("/+$", "");
            if (path.endsWith("/chat/completions") || path.endsWith("/embeddings")) throw new IllegalArgumentException();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port) + path;
        } catch (IllegalArgumentException e) {
            throw invalid("请输入有效的 HTTP(S) Base URL，使用接口根地址");
        }
    }

    static String choice(JsonNode body, String field, String... allowed) {
        String value = text(body, field, 64, true);
        if (!Arrays.asList(allowed).contains(value)) throw invalid("不支持的选项：" + field);
        return value;
    }

    static String text(JsonNode body, String field, int limit, boolean required) {
        JsonNode node = body.path(field);
        if (!node.isMissingNode() && !node.isNull() && !node.isTextual()) throw invalid("字段格式无效：" + field);
        String value = node.isTextual() ? node.textValue().trim() : "";
        if (value.length() > limit || (required && value.isEmpty())) throw invalid("字段缺失或过长：" + field);
        return value.isEmpty() ? null : value;
    }

    static IdentityException invalid(String message) { return new IdentityException(400, message); }
}
