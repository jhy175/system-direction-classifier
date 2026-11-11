package cn.edu.whu.irlab.systemdirectionclassifier.manager.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@Profile("mysql")
class ModelConnectionProbe {
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int RESPONSE_LIMIT = 128 * 1024;
    private static final String PROMPT = "只回复“连接成功”，不要添加其他内容。";

    private final ObjectMapper json;
    private final Set<String> privateEndpoints = new HashSet<>();
    private final Semaphore running = new Semaphore(4);
    private final OkHttpClient http = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build();

    ModelConnectionProbe(ObjectMapper json,
                         @Value("${classifier.model.private-base-urls:}") String allowed) {
        this.json = json;
        for (String url : allowed.split(",")) {
            if (!url.trim().isEmpty()) privateEndpoints.add(ModelConnectionConfig.url(url.trim()));
        }
    }

    void validateAddress(String address) {
        HttpUrl url = HttpUrl.parse(address);
        if (url == null || (!url.isHttps() && !privateEndpoints.contains(address))) {
            throw ModelConnectionConfig.invalid("公网模型地址须使用 HTTPS。本地服务地址由部署配置授权");
        }
        if (!privateEndpoints.contains(address) && !url.host().contains(".")) {
            throw ModelConnectionConfig.invalid("请填写公网域名，或使用部署配置授权的本地服务地址");
        }
    }

    Result run(ModelConnectionConfig config, String address, String key) {
        if (!running.tryAcquire()) {
            throw new cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException(429, "模型测试繁忙，请稍后重试");
        }
        long started = System.nanoTime();
        Result result = new Result();
        try {
            validateAddress(address);
            if (!"openai_compatible".equals(config.protocol)) {
                return result.fail("unsupported_protocol", "暂未实现此接口协议");
            }
            boolean embedding = "embedding".equals(config.type);
            HttpUrl url = HttpUrl.get(address + (embedding ? "/embeddings" : "/chat/completions"));
            OkHttpClient client = client(address, 25);
            Request.Builder request = new Request.Builder().url(url).header("Accept", "application/json")
                .post(RequestBody.create(JSON, json.writeValueAsBytes(payload(config, embedding))));
            if (key != null) request.header("Authorization", "Bearer " + key);
            try (Response response = client.newCall(request.build()).execute()) {
                result.reachable = true;
                result.httpStatus = response.code();
                result.requestId = safeId(response.header("x-request-id"), key, 160);
                if (!response.isSuccessful()) return httpError(result);
                try {
                    byte[] data = response.peekBody(RESPONSE_LIMIT + 1).bytes();
                    if (data.length > RESPONSE_LIMIT) return result.fail("invalid_response", "模型响应超出测试大小限制");
                    JsonNode body = json.readTree(data);
                    if (body == null || !body.isObject()) return result.fail("invalid_response", "模型响应格式无效");
                    return parseResult(result, body, embedding, key);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    return result.fail("invalid_response", "模型响应不是有效的 JSON");
                }
            }
        } catch (BlockedAddress e) {
            return result.fail("address_blocked", "服务地址解析到受限网络，请检查部署配置");
        } catch (SocketTimeoutException e) {
            return result.fail("timeout", "模型调用超时");
        } catch (java.io.InterruptedIOException e) {
            return result.fail("timeout", "模型调用超时");
        } catch (IOException e) {
            if (result.reachable == null) result.reachable = false;
            return result.fail("connection_error", "模型服务连接或读取失败");
        } finally {
            result.durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            running.release();
        }
    }

    private OkHttpClient client(String address, int timeoutSeconds) {
        boolean explicitlyAllowed = privateEndpoints.contains(address);
        return http.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .dns(host -> {
                List<InetAddress> addresses = Dns.SYSTEM.lookup(host);
                if (!explicitlyAllowed && addresses.stream().anyMatch(ip -> !publicAddress(ip))) throw new BlockedAddress();
                return addresses;
            }).build();
    }

    ModelConnectionManager.ChatReply complete(String address, String key, ObjectNode body, int timeoutSeconds) {
        validateAddress(address);
        Request.Builder request = new Request.Builder().url(address + "/chat/completions")
            .header("Accept", "application/json").post(RequestBody.create(JSON, body.toString()));
        if (key != null) request.header("Authorization", "Bearer " + key);
        ModelConnectionManager.ChatReply result = new ModelConnectionManager.ChatReply();
        try (Response response = client(address, timeoutSeconds).newCall(request.build()).execute()) {
            result.requestId = safeId(response.header("x-request-id"), key, 160);
            int maxBytes = 4 * 1024 * 1024;
            ResponseBody captured = response.peekBody(maxBytes + 1L);
            if (captured.contentLength() > maxBytes) return result.fail("response_too_large", "模型响应超出采集上限");
            result.raw = redact(captured.string(), key);
            if (!response.isSuccessful()) {
                Result error = new Result();
                error.httpStatus = response.code();
                httpError(error);
                return result.fail(error.errorCode, error.errorMessage + "（HTTP " + response.code() + "）");
            }
            try {
                JsonNode envelope = json.readTree(result.raw);
                if (envelope == null || !envelope.isObject()) return result.fail("invalid_response", "模型响应格式无效");
                JsonNode content = envelope.path("choices").path(0).path("message").path("content");
                result.usage = envelope.path("usage").isObject() ? envelope.path("usage").toString() : null;
                if (result.requestId == null) result.requestId = safeId(envelope.path("id").asText(null), key, 160);
                if (!content.isTextual() || content.asText().trim().isEmpty()) {
                    return result.fail("invalid_response", "模型未返回文本内容");
                }
                result.content = content.asText();
                return result;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return result.fail("invalid_response", "模型响应不是有效的 JSON");
            }
        } catch (BlockedAddress e) {
            return result.fail("address_blocked", "模型地址解析到受限网络");
        } catch (java.io.InterruptedIOException e) {
            return result.fail("timeout", "模型调用超时");
        } catch (IOException e) {
            return result.fail("connection_error", "模型服务连接或读取失败");
        }
    }

    String redact(String text, String key) {
        if (key == null || key.isEmpty()) return text;
        String encoded = json.valueToTree(key).toString();
        return text.replace(key, "[已脱敏]").replace(encoded.substring(1, encoded.length() - 1), "[已脱敏]");
    }

    private ObjectNode payload(ModelConnectionConfig config, boolean embedding) {
        ObjectNode body = json.createObjectNode().put("model", config.model);
        if (embedding) {
            body.put("input", "模型连接测试");
            return body;
        }
        body.putArray("messages").addObject().put("role", "user").put("content", PROMPT);
        body.put("temperature", 0).put("max_tokens", 16);
        if (config.adapter != null && config.adapter.path("enable_thinking").isBoolean()) {
            boolean thinking = config.adapter.path("enable_thinking").booleanValue();
            if ("dashscope".equals(config.provider)) body.put("enable_thinking", thinking);
            if ("vllm".equals(config.provider)) body.putObject("chat_template_kwargs").put("enable_thinking", thinking);
        }
        return body;
    }

    private Result parseResult(Result result, JsonNode body, boolean embedding, String key) {
        ObjectNode usage = json.createObjectNode();
        for (String field : Arrays.asList("prompt_tokens", "completion_tokens", "total_tokens")) {
            JsonNode number = body.path("usage").path(field);
            if (number.isIntegralNumber() && number.canConvertToLong() && number.longValue() >= 0) usage.set(field, number);
        }
        if (embedding) {
            JsonNode vector = body.path("data").path(0).path("embedding");
            if (!vector.isArray() || vector.isEmpty()) return result.fail("invalid_response", "服务未返回有效向量");
            for (JsonNode coordinate : vector) {
                if (!coordinate.isNumber() || !Double.isFinite(coordinate.doubleValue())) {
                    return result.fail("invalid_response", "服务未返回有效向量");
                }
            }
            usage.put("embedding_dimension", vector.size());
        } else {
            JsonNode content = body.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().trim().isEmpty()) {
                return result.fail("invalid_response", "服务未返回可用的文本回复");
            }
            String preview = content.asText().trim();
            if (("连接成功".equals(preview) || "连接成功。".equals(preview)) && !preview.equals(key)) {
                result.preview = preview;
            }
        }
        result.returnedModel = safeId(body.path("model").asText(null), key, 255);
        result.usageJson = usage.isEmpty() ? null : usage.toString();
        result.success = true;
        return result;
    }

    private static Result httpError(Result result) {
        switch (result.httpStatus) {
            case 401: return result.fail("auth_failed", "服务拒绝了模型凭据");
            case 403: return result.fail("auth_failed", "服务拒绝了模型调用权限");
            case 404: return result.fail("not_found", "服务地址或模型不可用");
            case 429: return result.fail("rate_limited", "服务限制了请求频率或额度");
            default: return result.fail("http_error", "服务返回 HTTP 错误");
        }
    }

    private static String safeId(String value, String key, int limit) {
        if (value == null || value.length() > limit || !value.matches("[A-Za-z0-9_.:/-]+")
            || value.toLowerCase(Locale.ROOT).startsWith("sk-") || (key != null && value.contains(key))) return null;
        return value;
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] ip = address.getAddress();
        int first = ip[0] & 255;
        int second = ip[1] & 255;
        if (!(address instanceof Inet4Address)) {
            // Exclude translated and tunnel ranges that could encode an internal IPv4 destination.
            return (first & 224) == 32 && !(first == 32 && second == 2)
                && !(first == 32 && second == 1 && (ip[2] & 254) == 0 && (ip[3] & 255) == 0);
        }
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
            && !(first == 192 && second == 0) && !(first == 198 && (second == 18 || second == 19));
    }

    private static final class BlockedAddress extends java.net.UnknownHostException { }

    static final class Result {
        Boolean reachable;
        Integer httpStatus;
        long durationMs;
        boolean success;
        String requestId, returnedModel, usageJson, preview, errorCode, errorMessage;

        Result fail(String code, String message) {
            success = false;
            errorCode = code;
            errorMessage = message;
            return this;
        }
    }
}
