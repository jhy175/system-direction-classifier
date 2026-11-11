package cn.edu.whu.irlab.systemdirectionclassifier.manager.method;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class MethodConfiguration {
    static final String IMPLEMENTATION = "api-core-hierarchical-llm";
    static final String SCHEMA = "hierarchical-llm-1";
    static final List<String> STAGES = Arrays.asList("parse", "router", "recall", "judge", "post", "result");
    static final Set<String> MODEL_STAGES = new HashSet<>(Arrays.asList("router", "recall", "judge"));
    private static final Map<String,Set<String>> SETTINGS = new HashMap<>();
    private static final Set<String> CALL_SETTINGS = new HashSet<>(Arrays.asList(
        "temperature", "max_tokens", "timeout_seconds", "llm_attempt_limit", "enable_thinking"));
    static {
        SETTINGS.put("parse", Collections.singleton("recall_max_abstract_chars"));
        SETTINGS.put("router", new HashSet<>(Arrays.asList("min_clusters", "max_clusters")));
        SETTINGS.put("recall", new HashSet<>(Arrays.asList("min_candidates", "max_candidates")));
        SETTINGS.put("post", Collections.singleton("high_relevance_cap"));
        SETTINGS.put("result", Collections.singleton("top_k_default"));
    }

    private MethodConfiguration() {}

    static final class Stage {
        final long id;
        final String code, strategy, outputSchema;
        final ObjectNode config, prompts, call, output;
        final Long originalModel;
        final String modelLabel;
        Long model;

        Stage(Map<String,Object> row, ObjectMapper json) {
            id = id(row, "id");
            code = (String)row.get("stageCode");
            strategy = (String)row.get("strategyCode");
            outputSchema = (String)row.get("outputSchemaVersion");
            config = object(json, row.get("configJson"));
            prompts = object(json, row.get("promptTemplatesJson"));
            call = object(json, row.get("callOptionsJson"));
            output = object(json, row.get("outputContractJson"));
            originalModel = id(row, "modelRevisionId");
            model = originalModel;
            modelLabel = (String)row.get("modelLabel");
        }

        Map<String,Object> view() {
            return fields("stageCode", code, "strategyCode", strategy, "config", config, "prompts", prompts,
                "callOptions", call, "outputSchemaVersion", outputSchema, "outputContract", output,
                "modelRevisionId", model, "modelLabel", modelLabel);
        }

        Map<String,Object> fingerprint() {
            return fields("stage_code", code, "strategy_code", strategy, "config_json", config,
                "prompt_templates_json", prompts, "call_options_json", call, "output_schema_version", outputSchema,
                "output_contract_json", output, "model_revision_id", model);
        }
    }

    static void apply(JsonNode input, Map<String,Stage> stages, Set<Long> availableModels) {
        JsonNode edits = input.path("stageChanges");
        if (edits.isMissingNode()) return;
        if (!edits.isArray() || edits.size() > STAGES.size()) throw invalid("阶段配置格式无效");
        Set<String> seen = new HashSet<>();
        for (JsonNode edit : edits) {
            String code = text(edit, "stageCode", 32, true);
            Stage stage = stages.get(code);
            if (stage == null || !seen.add(code)) throw invalid("未知或重复的阶段：" + code);
            Set<String> allowed = new HashSet<>(Arrays.asList("stageCode", "config"));
            if (MODEL_STAGES.contains(code)) allowed.addAll(Arrays.asList("prompts", "callOptions", "modelRevisionId"));
            keys(edit, allowed);
            merge(stage.config, edit.get("config"), SETTINGS.getOrDefault(code, Collections.emptySet()), "config");
            if (!MODEL_STAGES.contains(code)) continue;
            merge(stage.prompts, edit.get("prompts"), new HashSet<>(Arrays.asList("system", "user")), "prompts");
            Set<String> calls = new HashSet<>(CALL_SETTINGS);
            if ("judge".equals(code)) calls.addAll(Arrays.asList("candidate_attempt_limit", "candidate_backoff_seconds_per_attempt"));
            merge(stage.call, edit.get("callOptions"), calls, "callOptions");
            if (edit.has("modelRevisionId")) {
                Long chosen = optionalId(edit, "modelRevisionId");
                if (chosen != null && !chosen.equals(stage.originalModel) && !availableModels.contains(chosen)) {
                    throw invalid("阶段 " + code + " 的模型修订当前不可用");
                }
                stage.model = chosen;
            }
        }
    }

    private static void merge(ObjectNode target, JsonNode patch, Set<String> allowed, String section) {
        if (patch == null) return;
        if (target == null) throw invalid("当前策略不支持修改 " + section);
        keys(patch, allowed);
        patch.fields().forEachRemaining(item -> {
            JsonNode value = item.getValue();
            boolean valid = "prompts".equals(section) ? value.isTextual() && value.textValue().length() <= 100000
                : "enable_thinking".equals(item.getKey()) ? value.isBoolean() : value.isNumber();
            if (!valid && !value.isNull()) throw invalid("字段格式无效：" + item.getKey());
            target.set(item.getKey(), value);
        });
    }

    static void keys(JsonNode object, Set<String> allowed) {
        if (!object.isObject()) throw invalid("配置必须为对象");
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid("不支持的配置字段：" + name);
        });
    }

    static List<Map<String,Object>> validate(Map<String,Stage> stages, Set<Long> models) {
        List<Map<String,Object>> issues = new ArrayList<>();
        if (!stages.keySet().equals(new LinkedHashSet<>(STAGES))) {
            issue(issues, null, "stages", "方法缺少当前算法要求的阶段");
            return issues;
        }
        for (Stage stage : stages.values()) {
            String code = stage.code;
            JsonNode config = stage.config;
            switch (code) {
                case "parse":
                    range(issues, code, config, "recall_max_abstract_chars", 1, Integer.MAX_VALUE, true);
                    break;
                case "router":
                    bounds(issues, code, config, "min_clusters", "max_clusters");
                    break;
                case "recall":
                    bounds(issues, code, config, "min_candidates", "max_candidates");
                    break;
                case "post":
                    range(issues, code, config, "high_relevance_cap", 0, Integer.MAX_VALUE, true);
                    break;
                case "result":
                    range(issues, code, config, "top_k_default", config.path("top_k_min").asInt(1), config.path("top_k_max").asInt(5), true);
                    break;
                default:
                    break;
            }
            if (!MODEL_STAGES.contains(code)) continue;
            if (stage.model == null || !models.contains(stage.model)) issue(issues, code, "modelRevisionId", "请选择有权使用的对话模型修订");
            if (stage.prompts == null || stage.prompts.path("system").asText("").trim().isEmpty()) {
                issue(issues, code, "system", "系统提示词不能为空");
            }
            if (stage.prompts == null || !validTemplate(stage.prompts)) {
                issue(issues, code, "user", "提示词须包含约定变量，且 JSON 字面大括号须使用双大括号");
            }
            JsonNode call = stage.call;
            range(issues, code, call, "temperature", 0, 2, false);
            for (String key : Arrays.asList("max_tokens", "timeout_seconds", "llm_attempt_limit")) {
                range(issues, code, call, key, 1, Integer.MAX_VALUE, true);
            }
            if (call == null || !call.path("enable_thinking").isBoolean()) issue(issues, code, "enable_thinking", "请选择思考模式");
            if ("judge".equals(code)) {
                range(issues, code, call, "candidate_attempt_limit", 1, Integer.MAX_VALUE, true);
                range(issues, code, call, "candidate_backoff_seconds_per_attempt", 0, Integer.MAX_VALUE, false);
            }
        }
        return issues;
    }

    private static void bounds(List<Map<String,Object>> issues, String code, JsonNode config, String min, String max) {
        if (range(issues, code, config, min, 0, Integer.MAX_VALUE, true) &&
            range(issues, code, config, max, 1, Integer.MAX_VALUE, true) && config.path(min).asInt() > config.path(max).asInt()) {
            issue(issues, code, min, "下限不能大于上限");
        }
    }

    private static boolean range(List<Map<String,Object>> issues, String code, JsonNode source, String field,
                                 double min, double max, boolean integer) {
        JsonNode value = source == null ? null : source.get(field);
        boolean valid = value != null && value.isNumber() && (!integer || value.isIntegralNumber()) &&
            value.doubleValue() >= min && value.doubleValue() <= max;
        if (!valid) issue(issues, code, field, "请填写 " + field + "，范围 " + min + " 至 " + max);
        return valid;
    }

    private static boolean validTemplate(ObjectNode prompts) {
        String template = prompts.path("user").asText("");
        if (template.trim().isEmpty() || !"python_format".equals(prompts.path("syntax").asText())) return false;
        Set<String> variables = new HashSet<>();
        Set<String> expected = new HashSet<>();
        prompts.path("variables").forEach(node -> expected.add(node.asText()));
        for (int i = 0; i < template.length(); i++) {
            char token = template.charAt(i);
            if (token != '{' && token != '}') continue;
            if (i + 1 < template.length() && template.charAt(i + 1) == token) {
                i++;
                continue;
            }
            if (token == '}') return false;
            int end = template.indexOf('}', i + 1);
            if (end < 0) return false;
            String name = template.substring(i + 1, end);
            if (!expected.contains(name)) return false;
            variables.add(name);
            i = end;
        }
        return variables.equals(expected);
    }

    static void issue(List<Map<String,Object>> issues, String stage, String field, String message) {
        issues.add(fields("stage", stage, "field", field, "message", message));
    }

    static String hash(ObjectMapper json, Map<String,Object> snapshot) {
        try {
            Object normalized = json.convertValue(snapshot, Object.class);
            byte[] canonical = json.writer().without(SerializationFeature.INDENT_OUTPUT)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(normalized);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot hash method configuration", error);
        }
    }

    static ObjectNode object(ObjectMapper json, Object stored) {
        if (stored == null) return null;
        try {
            JsonNode node = json.readTree(stored.toString());
            if (node.isObject()) return (ObjectNode)node;
        } catch (JsonProcessingException ignored) { /* Invalid stored configuration is reported below. */ }
        throw conflict("无法读取保存的方法配置");
    }

    static String serialized(JsonNode node) { return node == null ? null : node.toString(); }

    static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put((String)pairs[i], pairs[i + 1]);
        return row;
    }

    static Long id(Map<String,Object> row, String field) {
        Number value = (Number)row.get(field);
        return value == null ? null : value.longValue();
    }

    static Long optionalId(JsonNode input, String field) {
        JsonNode node = input.path(field);
        if (node.isNull() || node.isMissingNode()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) throw invalid("无效的 " + field);
        return node.asLong();
    }

    static long requiredId(JsonNode input, String field) {
        Long value = optionalId(input, field);
        if (value == null) throw invalid("请提供 " + field);
        return value;
    }

    static String text(JsonNode input, String field, int max, boolean required) {
        JsonNode node = input.path(field);
        if (!node.isMissingNode() && !node.isNull() && !node.isTextual()) throw invalid(field + " 必须为文本");
        String value = node.asText("").trim();
        if (value.length() > max || (required && value.isEmpty())) throw invalid("请填写有效的 " + field + "（最多 " + max + " 字符）");
        return value.isEmpty() ? null : value;
    }

    static IdentityException invalid(String message) { return new IdentityException(400, message); }
    static IdentityException conflict(String message) { return new IdentityException(409, message); }
    static IdentityException denied() { return new IdentityException(403, "没有所需的识别方法操作权限"); }
    static IdentityException missing() { return new IdentityException(404, "识别方法或版本不存在"); }
}
