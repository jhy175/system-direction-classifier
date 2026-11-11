package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Database-backed adapter for the api-core hierarchical LLM method. */
final class RecognitionAlgorithm {
    static final List<String> STAGES = Collections.unmodifiableList(Arrays.asList("parse", "router", "recall", "judge", "post", "result"));
    static final String BUILD = "java-api-core-hierarchical-llm-v1";
    private static final Pattern MARKER = Pattern.compile("(摘要|Abstract|关键词|Keywords)\\s*[:：]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{|\\}\\}|\\{([a-zA-Z_][a-zA-Z_0-9]*)\\}");
    private static final String DEFAULT_LEVEL = "低度相关";
    private static final String FILL_LEVEL = "补充候选";
    private final ObjectMapper json;

    RecognitionAlgorithm(ObjectMapper json) { this.json = json; }

    ObjectNode parse(JsonNode input, JsonNode config) {
        ObjectNode fields = json.createObjectNode();
        ObjectNode sources = json.createObjectNode();
        String[] keys = {"title", "keywords", "abstract"};
        boolean structured = Arrays.stream(keys).anyMatch(key -> !norm(input.path(key).asText()).isEmpty());
        for (String key : keys) {
            String value = structured ? norm(input.path(key).asText()) : "";
            fields.put(key, value);
            sources.put(key, value.isEmpty() ? "missing" : key);
        }
        if (!structured) {
            String text = norm(input.path("text").asText());
            Matcher markers = MARKER.matcher(text);
            String field = "title";
            int start = 0;
            while (markers.find()) {
                putParsed(fields, sources, field, text.substring(start, markers.start()));
                String marker = markers.group(1);
                field = "摘要".equals(marker) || "abstract".equalsIgnoreCase(marker) ? "abstract" : "keywords";
                start = markers.end();
            }
            putParsed(fields, sources, field, text.substring(start));
        }
        for (String[] pair : new String[][]{{"journal_name","journal"},{"doi","doi"},{"pub_year","year"}}) {
            String value = norm(input.path(pair[1]).asText());
            fields.put(pair[0], value);
            sources.put(pair[0], value.isEmpty() ? "missing" : pair[1]);
        }
        ObjectNode output = json.createObjectNode();
        output.set("parsed_fields", fields);
        output.set("field_sources", sources);
        ArrayNode missing = output.putArray("missing_fields");
        sources.fields().forEachRemaining(entry -> { if ("missing".equals(entry.getValue().asText())) missing.add(entry.getKey()); });
        String original = fields.path("abstract").asText();
        String cleaned = original.replaceAll("(?U)\\s+", " ");
        int limit = config.path("recall_max_abstract_chars").asInt();
        int length = cleaned.codePointCount(0, cleaned.length());
        String shortened = cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(length, limit)));
        output.putObject("cleaning_and_truncation").put("recall_abstract_limit", limit)
            .put("recall_abstract_cleaned", !original.equals(cleaned)).put("recall_abstract_truncated", length > limit);
        output.put("recall_text", "标题：" + fields.path("title").asText() + "\n关键词：" + fields.path("keywords").asText()
            + "\n期刊：" + fields.path("journal_name").asText() + "\n摘要：" + shortened);
        ObjectNode judge = output.putObject("judgment_fields");
        for (JsonNode key : config.path("judge_fields")) judge.set(key.asText(), fields.path(key.asText()));
        return output;
    }

    private static String norm(String text) {
        String value = text.trim();
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    private static void putParsed(ObjectNode fields, ObjectNode sources, String key, String text) {
        String value = text.trim();
        fields.put(key, value);
        sources.put(key, value.isEmpty() ? "missing" : "text");
    }

    ArrayNode clusterBrief(Map<String,ObjectNode> clusters) {
        ArrayNode brief = json.createArrayNode();
        clusters.values().forEach(cluster -> {
            ObjectNode item = brief.addObject();
            for (String key : Arrays.asList("cluster_id", "cluster_name", "cluster_summary", "keywords")) item.set(key, cluster.path(key));
        });
        return brief;
    }

    ObjectNode route(JsonNode response, Map<String,ObjectNode> clusters, JsonNode config) {
        ObjectNode output = json.createObjectNode();
        output.set("available_clusters", clusterBrief(clusters));
        ArrayNode selected = output.putArray("selected_clusters");
        Set<String> seen = new HashSet<>();
        int max = config.path("max_clusters").asInt();
        for (JsonNode row : items(response, "clusters")) {
            String code = norm(row.path("cluster_id").asText());
            ObjectNode cluster = clusters.get(code);
            if (selected.size() >= max) break;
            if (cluster != null && seen.add(code)) {
                String level = row.path("relevance_level").asText().trim();
                selected.add(routed(cluster, selected.size() + 1, level.isEmpty() ? DEFAULT_LEVEL : level, row.path("reason").asText(), false));
            }
        }
        int min = Math.min(max, config.path("min_clusters").asInt());
        for (ObjectNode cluster : clusters.values()) {
            if (selected.size() >= min) break;
            if (seen.add(cluster.path("cluster_id").asText())) {
                selected.add(routed(cluster, selected.size() + 1, FILL_LEVEL, "为避免召回遗漏补充进入候选簇。", true));
            }
        }
        return output;
    }

    private static ObjectNode routed(ObjectNode cluster, int rank, String level, String reason, boolean filled) {
        return cluster.deepCopy().put("route_rank", rank).put("route_level", level)
            .put("route_reason", reason.trim()).put("supplemented", filled);
    }

    Map<String,ArrayNode> pool(JsonNode selectedClusters) {
        Map<String,ArrayNode> pool = new LinkedHashMap<>();
        for (JsonNode cluster : selectedClusters) {
            for (JsonNode code : cluster.path("member_codes")) {
                pool.computeIfAbsent(code.asText(), unused -> json.createArrayNode()).add(cluster.path("cluster_id"));
            }
        }
        return pool;
    }

    ArrayNode recallBrief(Map<String,ArrayNode> pool, Map<String,ObjectNode> disciplines) {
        ArrayNode brief = json.createArrayNode();
        for (String code : pool.keySet()) {
            ObjectNode source = disciplines.get(code);
            if (source == null || !source.path("has_summary").asBoolean()) continue;
            ObjectNode item = brief.addObject();
            for (String key : Arrays.asList("category_id", "category_name", "summary", "keywords")) item.set(key, source.path(key));
        }
        return brief;
    }

    ObjectNode recall(JsonNode response, JsonNode brief, Map<String,ArrayNode> pool,
                      Map<String,ObjectNode> disciplines, JsonNode config) {
        ObjectNode output = json.createObjectNode();
        output.set("candidate_pool", brief);
        ArrayNode selected = output.putArray("selected_candidates");
        Set<String> seen = new HashSet<>();
        Set<String> valid = new HashSet<>();
        brief.forEach(item -> valid.add(item.path("category_id").asText()));
        int max = config.path("max_candidates").asInt();
        for (JsonNode row : items(response, "candidates")) {
            String code = norm(row.path("category_id").asText());
            if (selected.size() >= max) break;
            if (!valid.contains(code) || !seen.add(code)) continue;
            ObjectNode candidate = candidate(disciplines.get(code), "model", selected.size() + 1, pool.get(code));
            String level = row.path("relevance_level").asText().trim();
            if (level.isEmpty()) level = DEFAULT_LEVEL;
            double score = row.path("score").asDouble(Double.NaN);
            boolean provided = Double.isFinite(score);
            if (!provided) score = config.path("level_score_fallback").path(level)
                .asDouble(config.path("unknown_level_score_fallback").asDouble(0.2));
            candidate.set("raw_score", row.has("score") ? row.get("score") : json.nullNode());
            selected.add(candidate.put("recall_level", level).put("recall_reason", row.path("reason").asText().trim())
                .put("effective_score", Math.max(0, Math.min(1, score))).put("score_source", provided ? "model" : "level_fallback"));
        }
        int minimum = Math.min(max, config.path("min_candidates").asInt());
        for (String code : pool.keySet()) {
            if (selected.size() >= minimum) break;
            if (valid.contains(code) && seen.add(code)) {
                selected.add(fill(disciplines.get(code), pool.get(code), selected.size() + 1, "cluster_fill",
                    config.path("level_score_fallback").path(FILL_LEVEL).asDouble(0.15),
                    "LLM 返回候选不足，从相关簇补足最低候选数量。"));
            }
        }
        for (JsonNode value : config.path("fallback_discipline_order")) {
            if (selected.size() >= minimum) break;
            String code = value.asText();
            if (disciplines.containsKey(code) && seen.add(code)) {
                selected.add(fill(disciplines.get(code), json.createArrayNode(), selected.size() + 1, "global_fallback",
                    config.path("whole_library_supplement_score").asDouble(0.05), "全库兜底补足最低召回数量。"));
            }
        }
        return output;
    }

    private ObjectNode fill(ObjectNode source, ArrayNode clusters, int rank, String origin, double score, String reason) {
        return candidate(source, origin, rank, clusters).put("recall_level", FILL_LEVEL).put("recall_reason", reason)
            .put("effective_score", score).put("score_source", origin);
    }

    ObjectNode candidate(ObjectNode source, String origin, int rank, ArrayNode clusters) {
        ObjectNode row = json.createObjectNode().put("discipline_id", source.path("discipline_id").longValue())
            .put("discipline_code", source.path("category_id").asText()).put("discipline_name", source.path("category_name").asText())
            .put("candidate_source", origin).put("recall_rank", rank);
        row.set("source_clusters", clusters);
        return row;
    }

    JsonNode judgment(JsonNode response, JsonNode candidate, ObjectNode portrait, JsonNode scores) {
        JsonNode item = response.path("results").isArray() ? response.path("results").path(0)
            : response.isArray() ? response.path(0) : response;
        String level = item.path("relevance_level").asText().trim();
        if (level.contains("高度")) level = "高度相关";
        else if (level.contains("中度")) level = "中度相关";
        else if (level.contains("低度")) level = "低度相关";
        else if (level.contains("不相关")) level = "基本不相关";
        if (!scores.has(level)) throw new InvalidAnswer("模型未返回有效的精判等级");
        ObjectNode result = ((ObjectNode)candidate).deepCopy();
        result.set("judgment_portrait", portrait);
        result.put("raw_level", level).put("judgment_failed", false);
        for (String key : Arrays.asList("reason", "confidence", "matched_module", "boundary_explanation",
            "paper_core_problem", "paper_main_contribution")) {
            String value = item.path(key).asText().trim();
            result.put(key, "reason".equals(key) && value.isEmpty() ? "模型未提供精判理由" : value);
        }
        for (String key : Arrays.asList("matched_fields", "matched_features", "paper_main_methods")) {
            result.set(key, item.path(key).isArray() ? item.path(key) : json.createArrayNode());
        }
        return result;
    }

    ObjectNode post(JsonNode judgments, JsonNode config, int topK) {
        List<ObjectNode> rows = new ArrayList<>();
        int multiplier = config.path("recall_score_multiplier").asInt(1000);
        judgments.forEach(item -> {
            ObjectNode row = ((ObjectNode)item).deepCopy();
            row.put("final_level", row.path("raw_level").asText())
                .put("sort_score", Math.rint(multiplier * row.path("effective_score").asDouble()));
            rows.add(row);
        });
        Comparator<ObjectNode> compare = Comparator.<ObjectNode>comparingInt(row -> config.path("level_scores")
                .path(row.path("final_level").asText()).asInt()).reversed()
            .thenComparing(Comparator.comparingDouble((ObjectNode row) -> row.path("sort_score").asDouble()).reversed())
            .thenComparingInt(row -> row.path("recall_rank").asInt());
        rows.sort(compare);
        int high = 0;
        for (int i = 0; i < rows.size(); i++) {
            ObjectNode row = rows.get(i).put("rank_before", i + 1);
            if ("高度相关".equals(row.path("raw_level").asText()) && ++high > config.path("high_relevance_cap").asInt()) {
                row.put("final_level", config.path("demote_to").asText()).put("adjustment", "high_relevance_cap")
                    .put("adjustment_reason", "高度相关候选超过方法设定上限，按排序降级。");
            }
        }
        rows.sort(compare);
        ObjectNode output = json.createObjectNode();
        ArrayNode ranked = output.putArray("complete_ranking");
        ArrayNode selected = output.putArray("disciplines");
        Set<String> excluded = new HashSet<>();
        config.path("excluded_levels").forEach(value -> excluded.add(value.asText()));
        for (int i = 0; i < rows.size(); i++) {
            ObjectNode row = rows.get(i).put("rank_after", i + 1);
            boolean irrelevant = excluded.contains(row.path("final_level").asText());
            boolean included = !irrelevant && selected.size() < topK;
            row.put("included", included);
            if (included) {
                row.put("final_rank", selected.size() + 1);
                selected.add(row);
            } else {
                row.put("filter_reason", irrelevant ? "基本不相关" : "超出输出 Top K");
            }
            ranked.add(row);
        }
        return output;
    }

    String render(String template, Map<String,String> variables) {
        Matcher matches = TEMPLATE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matches.find()) {
            String key = matches.group(1);
            String value = key == null ? matches.group().substring(0, 1) : variables.get(key);
            if (value == null) throw new IllegalStateException("Unknown prompt variable: " + key);
            matches.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        return matches.appendTail(rendered).toString();
    }

    private JsonNode items(JsonNode response, String key) {
        if (response.isArray()) return response;
        JsonNode rows = response.path(key);
        if (!rows.isArray() || rows.isEmpty()) rows = response.path("results");
        return rows.isArray() ? rows : json.createArrayNode();
    }

    static final class InvalidAnswer extends RuntimeException {
        InvalidAnswer(String message) { super(message); }
    }
}
