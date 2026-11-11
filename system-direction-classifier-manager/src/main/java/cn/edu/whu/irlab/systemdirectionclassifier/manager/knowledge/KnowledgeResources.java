package cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Profile("mysql")
@RequiredArgsConstructor
class KnowledgeResources {
    private static final String[] KINDS = {"profiles", "summaries", "clusters", "papers"};
    private static final String[] TASKS = {"core_tasks", "peripheral_tasks", "cross_tasks", "differentiation"};
    private static final String PROFILE = "demo_knowledge_discipline";
    private static final String SUMMARY = "demo_knowledge_summary";
    private static final String CLUSTER = "demo_knowledge_cluster";
    private static final String PAPER = "demo_knowledge_seed_paper";
    private final IdentityDAO db;
    private final ObjectMapper mapper;

    Map<String,Object> snapshot(long version, boolean full) {
        Map<String,Object> result = new LinkedHashMap<>();
        List<Map<String,Object>> profiles = db.query("SELECT id,code_snapshot AS code,name_snapshot AS name," +
            "content_hash,judgment_text_hash,text_sync_status" +
            (full ? ",portrait_json AS portrait,judgment_text" : "") +
            " FROM demo_knowledge_discipline WHERE knowledge_version_id=? ORDER BY code_snapshot", version);
        if (full) profiles.forEach(row -> parseFields(row, "portrait"));
        result.put("profiles", profiles);

        List<Map<String,Object>> summaries = db.query("SELECT s.id,p.code_snapshot AS code,p.name_snapshot AS name," +
            "s.summary_text AS summary,s.keywords_json AS keywords,s.provenance_status,s.source_portrait_hash," +
            "s.content_hash,s.source_asset_id," +
            "(s.provenance_status='stale' OR (s.source_portrait_hash IS NOT NULL AND NOT(s.source_portrait_hash <=> p.content_hash))) AS stale " +
            "FROM demo_knowledge_summary s JOIN demo_knowledge_discipline p ON p.id=s.knowledge_discipline_id " +
            "WHERE s.knowledge_version_id=? ORDER BY p.code_snapshot", version);
        summaries.forEach(row -> parseFields(row, "keywords"));
        result.put("summaries", summaries);

        List<Map<String,Object>> clusters = db.query("SELECT id,cluster_code AS code,name,summary_text AS description," +
            "keywords_json AS keywords,content_hash FROM demo_knowledge_cluster " +
            "WHERE knowledge_version_id=? ORDER BY sort_order,id", version);
        Map<Long,List<String>> members = relationships("demo_knowledge_cluster_member", "cluster_id", version, "r.sort_order,r.id");
        clusters.forEach(row -> {
            parseFields(row, "keywords");
            row.put("members", members.getOrDefault(id(row, "id"), Collections.emptyList()));
        });
        result.put("clusters", clusters);

        List<Map<String,Object>> papers = db.query("SELECT id,title,abstract,keywords_json AS keywords,authors_json AS authors," +
            "journal_name,publication_raw,publication_year,doi_raw,doi_normalized,source_system,source_record_id," +
            "raw_record_json AS raw_record,content_hash FROM demo_knowledge_seed_paper WHERE knowledge_version_id=? ORDER BY id", version);
        Map<Long,List<Map<String,Object>>> relations = new HashMap<>();
        for (Map<String,Object> row : db.query("SELECT r.seed_paper_id,p.code_snapshot AS code,r.use_for_generation " +
            "FROM demo_seed_paper_discipline r JOIN demo_knowledge_discipline p ON p.id=r.knowledge_discipline_id " +
            "WHERE r.knowledge_version_id=? ORDER BY p.code_snapshot", version)) {
            relations.computeIfAbsent(id(row, "seedPaperId"), ignored -> new ArrayList<>())
                .add(fields("code", row.get("code"), "useForGeneration", truth(row.get("useForGeneration"))));
        }
        papers.forEach(row -> {
            parseFields(row, "keywords", "authors", "rawRecord");
            row.put("disciplines", relations.getOrDefault(id(row, "id"), Collections.emptyList()));
        });
        result.put("papers", papers);
        return result;
    }

    private Map<Long,List<String>> relationships(String table, String column, long version, String order) {
        Map<Long,List<String>> result = new HashMap<>();
        for (Map<String,Object> row : db.query("SELECT r." + column + " AS resource_id,p.code_snapshot AS code FROM " +
            table + " r JOIN demo_knowledge_discipline p ON p.id=r.knowledge_discipline_id " +
            "WHERE r.knowledge_version_id=? ORDER BY " + order, version)) {
            result.computeIfAbsent(id(row, "resourceId"), ignored -> new ArrayList<>()).add((String)row.get("code"));
        }
        return result;
    }

    Map<String,Object> portrait(long version, long profile) {
        Map<String,Object> row = scoped(PROFILE, version, profile);
        Map<String,Object> result = fields("id", profile, "code", row.get("codeSnapshot"), "name", row.get("nameSnapshot"),
            "portrait", row.get("portraitJson"), "judgmentText", row.get("judgmentText"),
            "textSyncStatus", row.get("textSyncStatus"), "sourceVersion", row.get("sourcePortraitVersion"));
        parseFields(result, "portrait");
        return result;
    }

    Object manifest(long version) {
        return snapshot(version, false);
    }

    void copy(long source, long target) {
        copyRows(PROFILE, "taxonomy_id,discipline_id,source_code,code_snapshot,name_snapshot,source_label,source_tag_raw," +
            "source_portrait_version,portrait_schema_version,portrait_json,original_definition,final_definition,expanded_portraits," +
            "judgment_text,judgment_projection_version,text_sync_status,content_hash,judgment_text_hash,source_asset_id,source_locator_json",
            source, target);
        copyRows(CLUSTER, "cluster_code,name,summary_text,keywords_json,sort_order,source_input_hash,provenance_json," +
            "content_hash,source_asset_id,source_locator_json", source, target);
        db.execute("INSERT INTO demo_knowledge_summary (knowledge_version_id,knowledge_discipline_id,summary_text," +
            "keywords_json,source_portrait_hash,source_input_hash,provenance_status,provenance_json,generation_item_id,content_hash," +
            "source_asset_id,source_locator_json) SELECT ?,p2.id,s.summary_text,s.keywords_json,s.source_portrait_hash," +
            "s.source_input_hash,s.provenance_status,s.provenance_json,s.generation_item_id,s.content_hash,s.source_asset_id,s.source_locator_json " +
            "FROM demo_knowledge_summary s JOIN demo_knowledge_discipline p1 ON p1.id=s.knowledge_discipline_id " +
            "JOIN demo_knowledge_discipline p2 ON p2.knowledge_version_id=? AND p2.discipline_id=p1.discipline_id " +
            "WHERE s.knowledge_version_id=?", target, target, source);
        db.execute("INSERT INTO demo_knowledge_cluster_member (knowledge_version_id,cluster_id,knowledge_discipline_id,sort_order) " +
            "SELECT ?,c2.id,p2.id,m.sort_order FROM demo_knowledge_cluster_member m " +
            "JOIN demo_knowledge_cluster c1 ON c1.id=m.cluster_id " +
            "JOIN demo_knowledge_cluster c2 ON c2.knowledge_version_id=? AND c2.cluster_code=c1.cluster_code " +
            "JOIN demo_knowledge_discipline p1 ON p1.id=m.knowledge_discipline_id " +
            "JOIN demo_knowledge_discipline p2 ON p2.knowledge_version_id=? AND p2.discipline_id=p1.discipline_id " +
            "WHERE m.knowledge_version_id=?", target, target, target, source);
        String paperColumns = "title,abstract,keywords_json,authors_json,journal_name,publication_raw,publication_year," +
            "doi_raw,doi_normalized,source_system,source_record_id,raw_record_json,source_asset_id,source_locator_json,content_hash";
        for (Map<String,Object> old : db.query("SELECT id," + paperColumns + " FROM demo_knowledge_seed_paper WHERE knowledge_version_id=?", source)) {
            Map<String,Object> columns = fields("knowledge_version_id", target);
            for (String name : paperColumns.split(",")) columns.put(name, old.get(camel(name)));
            long paper = db.insert(PAPER, columns);
            db.execute("INSERT INTO demo_seed_paper_discipline (knowledge_version_id,seed_paper_id,knowledge_discipline_id," +
                "use_for_generation,assignment_source,remark) SELECT ?,?,p2.id,r.use_for_generation,r.assignment_source,r.remark " +
                "FROM demo_seed_paper_discipline r JOIN demo_knowledge_discipline p1 ON p1.id=r.knowledge_discipline_id " +
                "JOIN demo_knowledge_discipline p2 ON p2.knowledge_version_id=? AND p2.discipline_id=p1.discipline_id " +
                "WHERE r.knowledge_version_id=? AND r.seed_paper_id=?", target, paper, target, source, old.get("id"));
        }
    }

    private void copyRows(String table, String columns, long source, long target) {
        db.execute("INSERT INTO " + table + " (knowledge_version_id," + columns + ") " +
            "SELECT ?," + columns + " FROM " + table + " WHERE knowledge_version_id=?", target, source);
    }

    long save(String kind, long version, long taxonomy, JsonNode item, Long asset) {
        Long existing = optionalNumber(item, "id");
        switch (kind) {
            case "profiles": return saveProfile(version, taxonomy, item, existing, asset);
            case "summaries": return saveSummary(version, item, existing, asset, "manual");
            case "clusters": return saveCluster(version, item, existing, asset);
            case "papers": return savePaper(version, item, existing, asset);
            default: throw missing();
        }
    }

    void remove(String kind, long version, long resource) {
        switch (kind) {
            case "profiles":
                scoped(PROFILE, version, resource);
                if (db.count("SELECT COUNT(*) FROM demo_knowledge_cluster_member WHERE knowledge_discipline_id=?", resource) > 0 ||
                    db.count("SELECT COUNT(*) FROM demo_seed_paper_discipline WHERE knowledge_discipline_id=?", resource) > 0) {
                    throw conflict("该学科仍被学科簇或种子论文引用，请先调整关联");
                }
                db.execute("DELETE FROM demo_knowledge_summary WHERE knowledge_discipline_id=?", resource);
                delete(PROFILE, version, resource);
                break;
            case "summaries": delete(SUMMARY, version, resource); break;
            case "clusters":
                scoped(CLUSTER, version, resource);
                db.execute("DELETE FROM demo_knowledge_cluster_member WHERE cluster_id=?", resource);
                delete(CLUSTER, version, resource);
                break;
            case "papers":
                scoped(PAPER, version, resource);
                db.execute("DELETE FROM demo_seed_paper_discipline WHERE seed_paper_id=?", resource);
                delete(PAPER, version, resource);
                break;
            default: throw missing();
        }
    }

    private void delete(String table, long version, long resource) {
        if (db.execute("DELETE FROM " + table + " WHERE knowledge_version_id=? AND id=?", version, resource) != 1) throw missing();
    }

    private ObjectNode normalizedPortrait(JsonNode item) {
        String code = code(item, "code");
        String name = text(item, "name", 255, true);
        JsonNode raw = item.get("portrait");
        if (raw == null || !raw.isObject()) throw invalid(code + " 的画像必须为 JSON 对象");
        ObjectNode portrait = (ObjectNode)raw.deepCopy();
        for (String field : new String[]{"category_id","discipline_code"}) {
            if (portrait.hasNonNull(field) && !code.equals(portrait.get(field).asText().trim()))
                throw invalid(code + " 的画像内部代码不一致");
        }
        if (!portrait.has("discipline_code") || portrait.has("category_id")) portrait.put("category_id", code).put("category_name", name);
        if (portrait.has("discipline_code")) portrait.put("discipline_code", code).put("discipline_name", name);
        for (String task : TASKS) {
            JsonNode value = portrait.get(task);
            if (value == null || !value.isArray()) throw invalid(code + " 的 " + task + " 必须为 JSON 数组");
            for (JsonNode taskItem : value) if (!taskItem.isObject()) throw invalid(code + " 的任务数组必须包含对象");
        }
        for (String field : new String[]{"original_definition","final_definition","expanded_portraits"}) {
            text(portrait, field, 500000, false);
        }
        return portrait;
    }

    private long saveProfile(long version, long taxonomy, JsonNode item, Long existing, Long asset) {
        String code = code(item, "code");
        String name = text(item, "name", 255, true);
        ObjectNode portrait = normalizedPortrait(item);
        String taxonomyCode = (String)db.one("SELECT code FROM demo_taxonomy WHERE id=?", taxonomy).get("code");
        verifyTaxonomyCode(taxonomyCode, code);
        Map<String,Object> old = existing == null ? null : scoped(PROFILE, version, existing);
        if (old != null && !code.equals(old.get("codeSnapshot"))) throw invalid("编辑时学科代码不可变");
        String finalText = text(portrait, "final_definition", 500000, false);
        String original = text(portrait, "original_definition", 500000, false);
        String expanded = text(portrait, "expanded_portraits", 500000, false);
        String base = finalText == null ? Objects.toString(original, "") : finalText;
        String judgment = expanded == null || base.contains(expanded) ? base : base.isEmpty() ? expanded : base + "\n\n" + expanded;
        String status = "edited";
        if (old != null && !item.path("reviewed").asBoolean()) {
            JsonNode before = old.get("portraitJson") == null ? mapper.createObjectNode() : parse(old.get("portraitJson").toString());
            boolean changed = !Objects.equals(before.get("original_definition"), portrait.get("original_definition"));
            for (String task : TASKS) changed |= !Objects.equals(before.get(task), portrait.get(task));
            if ("needs_review".equals(old.get("textSyncStatus")) ||
                (changed && judgment.equals(old.get("judgmentText")))) status = "needs_review";
        }
        String contentHash = hash(portrait);
        if (old != null && portrait.equals(parse(Objects.toString(old.get("portraitJson"), "{}")))) {
            contentHash = (String)old.get("contentHash");
        }
        Map<String,Object> values = fields("name_snapshot", name, "portrait_json", json(portrait),
            "original_definition", portrait.path("original_definition").textValue(), "final_definition", portrait.path("final_definition").textValue(),
            "expanded_portraits", portrait.path("expanded_portraits").textValue(), "judgment_text", judgment,
            "judgment_projection_version", "api-core-judgment-1", "text_sync_status", status, "content_hash", contentHash,
            "judgment_text_hash", digest(judgment.getBytes(StandardCharsets.UTF_8)));
        if (old == null) {
            Map<String,Object> discipline = db.one("SELECT id FROM demo_discipline WHERE taxonomy_id=? AND canonical_code=?", taxonomy, code);
            if (discipline == null) {
                db.execute("INSERT INTO demo_discipline (taxonomy_id,canonical_code,display_name) VALUES (?,?,?) " +
                    "ON DUPLICATE KEY UPDATE id=id", taxonomy, code, name);
                discipline = db.one("SELECT id FROM demo_discipline WHERE taxonomy_id=? AND canonical_code=? FOR SHARE", taxonomy, code);
            }
            values.putAll(fields("taxonomy_id", taxonomy, "discipline_id", id(discipline, "id"), "source_code", code,
                "code_snapshot", code, "source_asset_id", asset, "source_portrait_version", text(portrait, "version", 255, false),
                "source_label", text(portrait, "source", 255, false), "source_tag_raw", text(portrait, "source_tag", 128, false),
                "portrait_schema_version", taxonomyCode + "-task-portrait-1"));
        }
        long profile = write(PROFILE, version, existing, values);
        if (old != null && !Objects.equals(contentHash, old.get("contentHash"))) {
            db.execute("UPDATE demo_knowledge_summary SET provenance_status='stale' WHERE knowledge_discipline_id=?", profile);
        }
        return profile;
    }

    private long saveSummary(long version, JsonNode item, Long existing, Long asset, String origin) {
        Map<String,Object> profile = profileByCode(version, code(item, "code"));
        long profileId = id(profile, "id");
        Map<String,Object> current = db.one("SELECT id FROM demo_knowledge_summary WHERE knowledge_version_id=? AND knowledge_discipline_id=?",
            version, profileId);
        if (existing != null && (current == null || !existing.equals(id(current, "id")))) throw missing();
        List<String> words = strings(item, "keywords", 500);
        String summary = text(item, "summary", 20000, true);
        Map<String,Object> values = fields("knowledge_discipline_id", profileId, "summary_text", summary,
            "keywords_json", json(words), "source_portrait_hash", profile.get("contentHash"), "source_input_hash", null,
            "provenance_status", origin, "provenance_json", json(fields("origin", origin)), "generation_item_id", null,
            "content_hash", hash(fields("summary", summary, "keywords", words)));
        if (asset != null) values.put("source_asset_id", asset);
        return write(SUMMARY, version, current == null ? null : id(current, "id"), values);
    }

    private long saveCluster(long version, JsonNode item, Long existing, Long asset) {
        if (existing != null) scoped(CLUSTER, version, existing);
        String code = code(item, "code");
        String name = text(item, "name", 255, true);
        String description = text(item, "description", 50000, false);
        List<String> words = strings(item, "keywords", 500);
        List<String> members = strings(item, "members", 64);
        List<Long> memberIds = members.stream().map(member -> id(profileByCode(version, member), "id")).collect(Collectors.toList());
        Map<String,Object> values = fields("cluster_code", code, "name", name, "summary_text", description,
            "keywords_json", json(words), "content_hash", hash(fields("code", code, "name", name,
                "description", description, "keywords", words, "members", members)));
        if (existing == null) values.put("sort_order", db.count(
            "SELECT COALESCE(MAX(sort_order),0)+1 FROM demo_knowledge_cluster WHERE knowledge_version_id=?", version));
        if (asset != null) values.put("source_asset_id", asset);
        long cluster = write(CLUSTER, version, existing, values);
        db.execute("DELETE FROM demo_knowledge_cluster_member WHERE cluster_id=?", cluster);
        for (int i = 0; i < memberIds.size(); i++) {
            db.execute("INSERT INTO demo_knowledge_cluster_member (knowledge_version_id,cluster_id,knowledge_discipline_id,sort_order) " +
                "VALUES (?,?,?,?)", version, cluster, memberIds.get(i), i + 1);
        }
        return cluster;
    }

    private Map<String,Object> paperFields(JsonNode item) {
        String doi = text(item, "doiRaw", 512, false);
        Long year = optionalNumber(item, "publicationYear");
        if (year != null && (year < 1000 || year > 9999)) throw invalid("出版年份须为四位数字");
        JsonNode raw = item.path("rawRecord");
        if (!raw.isMissingNode() && !raw.isNull() && !raw.isObject()) throw invalid("原始论文记录须为 JSON 对象");
        return fields("title", text(item, "title", 50000, true), "abstract", text(item, "abstract", 500000, false),
            "keywords_json", json(strings(item, "keywords", 500)), "authors_json", json(strings(item, "authors", 500)),
            "journal_name", text(item, "journalName", 512, false), "publication_raw", text(item, "publicationRaw", 255, false),
            "publication_year", year, "doi_raw", doi,
            "doi_normalized", doi != null && doi.matches("10\\.\\d{4,9}/\\S+") ? doi.toLowerCase(Locale.ROOT) : null,
            "source_system", text(item, "sourceSystem", 128, false), "source_record_id", text(item, "sourceRecordId", 255, false),
            "raw_record_json", raw.isObject() ? json(raw) : null);
    }

    private Map<String,Boolean> paperDisciplines(JsonNode item) {
        JsonNode values = item.path("disciplines");
        Map<String,Boolean> result = new LinkedHashMap<>();
        if (!values.isArray() || values.isEmpty() || values.size() > 1000) throw invalid("请选择种子论文所属学科");
        for (JsonNode relation : values) {
            String code = code(relation, "code");
            if (result.putIfAbsent(code, !relation.has("useForGeneration") || relation.path("useForGeneration").asBoolean()) != null)
                throw invalid("种子论文的学科代码重复：" + code);
        }
        return result;
    }

    private long savePaper(long version, JsonNode item, Long existing, Long asset) {
        if (existing != null) scoped(PAPER, version, existing);
        Map<String,Object> values = paperFields(item);
        Map<String,Boolean> relations = paperDisciplines(item);
        values.put("content_hash", hash(fields("paper", values, "disciplines", relations)));
        if (asset != null) values.put("source_asset_id", asset);
        long paper = write(PAPER, version, existing, values);
        db.execute("DELETE FROM demo_seed_paper_discipline WHERE seed_paper_id=?", paper);
        relations.forEach((code, use) -> db.execute("INSERT INTO demo_seed_paper_discipline " +
            "(knowledge_version_id,seed_paper_id,knowledge_discipline_id,use_for_generation,assignment_source) VALUES (?,?,?,?,?)",
            version, paper, id(profileByCode(version, code), "id"), use, asset == null ? "manual" : "import"));
        return paper;
    }

    Map<String,Object> validate(long version) {
        Map<String,Object> snapshot = snapshot(version, false);
        List<Map<String,Object>> profiles = list(snapshot, "profiles");
        List<Map<String,Object>> summaries = list(snapshot, "summaries");
        List<Map<String,Object>> clusters = list(snapshot, "clusters");
        List<String> issues = new ArrayList<>();
        Set<String> summarized = summaries.stream().map(row -> (String)row.get("code")).collect(Collectors.toSet());
        Map<String,Integer> coverage = new HashMap<>();
        for (Map<String,Object> cluster : clusters) {
            List<String> members = strings(mapper.valueToTree(cluster), "members", 64);
            for (String code : members) coverage.merge(code, 1, Integer::sum);
            if (members.isEmpty() || cluster.get("description") == null || cluster.get("name") == null)
                issues.add("学科簇缺少名称、描述或成员：" + cluster.get("code"));
        }
        if (profiles.isEmpty()) issues.add("当前版本没有学科画像");
        for (Map<String,Object> profile : profiles) {
            String code = (String)profile.get("code");
            if (profile.get("judgmentTextHash") == null || digest(new byte[0]).equals(profile.get("judgmentTextHash")) ||
                "needs_review".equals(profile.get("textSyncStatus"))) issues.add("请核对精判文本：" + code);
            if (!summarized.contains(code)) issues.add("缺少召回摘要：" + code);
            int count = coverage.getOrDefault(code, 0);
            if (count == 0) issues.add("未归簇：" + code);
            if (count > 1) issues.add("当前识别算法需要单一归簇：" + code);
        }
        long stale = summaries.stream().filter(row -> truth(row.get("stale"))).count();
        if (stale > 0) issues.add(stale + " 条召回摘要待更新");
        return fields("profiles", profiles.size(), "summaries", summaries.size(), "clusters", clusters.size(),
            "papers", list(snapshot, "papers").size(), "stale", stale, "compatible", issues.isEmpty(), "issues", issues);
    }

    Map<String,Object> inspectImport(long version, JsonNode input) {
        if (!input.isObject()) throw invalid("导入文件须包含 resources 对象");
        String taxonomy = (String)db.one("SELECT t.code FROM demo_knowledge_version v JOIN demo_taxonomy t ON t.id=v.taxonomy_id WHERE v.id=?", version).get("code");
        Map<String,Object> current = snapshot(version, false);
        Set<String> profiles = codes(list(current, "profiles"));
        Set<String> summaries = codes(list(current, "summaries"));
        Set<String> clusters = codes(list(current, "clusters"));
        List<String> issues = new ArrayList<>();
        Map<String,Object> counts = new LinkedHashMap<>();
        for (String kind : KINDS) {
            JsonNode items = input.path(kind);
            if (items.isMissingNode()) { counts.put(kind, 0); continue; }
            if (!items.isArray() || items.size() > 1000) throw invalid(kind + " 须为不超过 1000 条的数组");
            counts.put(kind, items.size());
            int index = 0;
            for (JsonNode item : items) {
                index++;
                try {
                    if (!item.isObject()) throw invalid("资源必须为 JSON 对象");
                    switch (kind) {
                        case "profiles":
                            normalizedPortrait(item);
                            String profile = code(item, "code");
                            verifyTaxonomyCode(taxonomy, profile);
                            if (!profiles.add(profile)) throw invalid("学科代码重复：" + profile);
                            break;
                        case "summaries":
                            String summary = code(item, "code");
                            text(item, "summary", 20000, true);
                            strings(item, "keywords", 500);
                            checkMembers(profiles, Collections.singleton(summary));
                            if (!summaries.add(summary)) throw invalid("召回摘要重复：" + summary);
                            break;
                        case "clusters":
                            String cluster = code(item, "code");
                            text(item, "name", 255, true);
                            text(item, "description", 50000, false);
                            strings(item, "keywords", 500);
                            checkMembers(profiles, strings(item, "members", 64));
                            if (!clusters.add(cluster)) throw invalid("学科簇代码重复：" + cluster);
                            break;
                        case "papers":
                            paperFields(item);
                            checkMembers(profiles, paperDisciplines(item).keySet());
                            break;
                        default: throw missing();
                    }
                } catch (IdentityException error) {
                    if (issues.size() < 100) issues.add(kind + " 第 " + index + " 条：" + error.getMessage());
                }
            }
        }
        if (counts.values().stream().allMatch(value -> Objects.equals(0, value))) issues.add("文件中没有可导入的资源");
        return fields("counts", counts, "issues", issues, "valid", issues.isEmpty());
    }

    void applyImport(long version, long taxonomy, JsonNode input, long batch, long asset) {
        for (String kind : KINDS) {
            int index = 0;
            for (JsonNode original : input.path(kind)) {
                ObjectNode item = original.deepCopy();
                item.remove("id");
                long resource = save(kind, version, taxonomy, item, asset);
                String locator = json(fields("json_pointer", "/resources/" + kind + "/" + index++));
                String table = "profiles".equals(kind) ? PROFILE : "summaries".equals(kind) ? SUMMARY :
                    "clusters".equals(kind) ? CLUSTER : PAPER;
                db.execute("UPDATE " + table + " SET source_locator_json=? WHERE id=?", locator, resource);
                db.insert("demo_knowledge_import_item", fields("import_id", batch, "source_asset_id", asset,
                    "source_locator_json", locator, "source_key", item.path("code").textValue(),
                    "validation_status", "valid", "action", "insert", "applied_resource_id", resource));
            }
        }
    }

    List<Map<String,Object>> generate(String kind, long version) {
        if ("summaries".equals(kind)) {
            List<Map<String,Object>> items = new ArrayList<>();
            for (Map<String,Object> row : db.query("SELECT code_snapshot,original_definition,judgment_text,portrait_json " +
                "FROM demo_knowledge_discipline WHERE knowledge_version_id=? ORDER BY code_snapshot", version)) {
                String summary = Objects.toString(row.get("originalDefinition"), Objects.toString(row.get("judgmentText"), ""))
                    .replaceAll("\\s+", " ").trim();
                if (summary.isEmpty()) continue;
                int end = summary.offsetByCodePoints(0, Math.min(220, summary.codePointCount(0, summary.length())));
                Set<String> keywords = new LinkedHashSet<>();
                if (row.get("portraitJson") != null) for (JsonNode task : parse(row.get("portraitJson").toString()).path("core_tasks")) {
                    String name = task.path("task_name").asText("").trim();
                    if (!name.isEmpty() && name.length() <= 500 && keywords.size() < 8) keywords.add(name);
                }
                items.add(fields("code", row.get("codeSnapshot"), "summary", summary.substring(0, end), "keywords", keywords));
            }
            return items;
        }
        if (!"clusters".equals(kind)) throw missing();
        Map<String,List<String>> groups = new LinkedHashMap<>();
        Map<String,Object> snapshot = snapshot(version, false);
        Map<String,String> first = new HashMap<>();
        for (Map<String,Object> item : list(snapshot, "summaries")) {
            JsonNode keyword = mapper.valueToTree(item.get("keywords"));
            if (keyword.isArray() && !keyword.isEmpty()) first.put((String)item.get("code"), keyword.get(0).asText().trim());
        }
        for (Map<String,Object> profile : list(snapshot, "profiles")) {
            String code = (String)profile.get("code");
            String key = first.getOrDefault(code, "");
            if (key.isEmpty()) key = (String)profile.get("name");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(code);
        }
        List<Map<String,Object>> items = new ArrayList<>();
        groups.forEach((key, members) -> items.add(fields("code", "C" + (items.size() + 1),
            "name", key.substring(0, Math.min(255, key.length())), "description", key + "相关学科",
            "keywords", Collections.singletonList(key), "members", members)));
        return items;
    }

    void applyGenerated(String kind, long version, long taxonomy, List<Map<String,Object>> rows) {
        if (rows.isEmpty()) throw invalid("当前草稿没有可生成的输入");
        if ("clusters".equals(kind)) {
            db.execute("DELETE FROM demo_knowledge_cluster_member WHERE knowledge_version_id=?", version);
            db.execute("DELETE FROM demo_knowledge_cluster WHERE knowledge_version_id=?", version);
        }
        for (Map<String,Object> row : rows) {
            JsonNode item = mapper.valueToTree(row);
            if ("summaries".equals(kind)) saveSummary(version, item, null, null, "local_extract");
            else save(kind, version, taxonomy, item, null);
        }
    }

    private void verifyTaxonomyCode(String taxonomy, String code) {
        if (("moe".equals(taxonomy) && !code.matches("[0-9]{2}[0-9A-Z]{2}")) ||
            ("wos_ssci".equals(taxonomy) && !code.matches("SSCI-[0-9]+")) ||
            ("wos_scie".equals(taxonomy) && !code.matches("SCIE-[0-9]+"))) {
            throw invalid("学科代码与学科体系不符：" + code);
        }
    }

    private void checkMembers(Set<String> allowed, Collection<String> codes) {
        for (String code : codes) if (!allowed.contains(code)) throw invalid("未知学科代码：" + code);
    }

    private Set<String> codes(List<Map<String,Object>> items) {
        return items.stream().map(item -> (String)item.get("code")).collect(Collectors.toSet());
    }

    private Map<String,Object> profileByCode(long version, String code) {
        Map<String,Object> row = db.one("SELECT id,content_hash FROM demo_knowledge_discipline WHERE knowledge_version_id=? AND code_snapshot=?",
            version, code);
        if (row == null) throw invalid("当前版本没有学科 " + code);
        return row;
    }

    private Map<String,Object> scoped(String table, long version, long id) {
        Map<String,Object> row = db.one("SELECT * FROM " + table + " WHERE knowledge_version_id=? AND id=?", version, id);
        if (row == null) throw missing();
        return row;
    }

    private long write(String table, long version, Long existing, Map<String,Object> values) {
        if (existing != null) {
            db.update(table, existing, values);
            return existing;
        }
        values.put("knowledge_version_id", version);
        return db.insert(table, values);
    }

    String json(Object value) {
        try { return mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(value); }
        catch (JsonProcessingException error) { throw invalid("无法保存资源 JSON"); }
    }

    JsonNode parse(byte[] raw) {
        try {
            JsonNode value = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (value == null) throw invalid("资源文件为空");
            return value;
        } catch (IOException error) {
            throw invalid("资源 JSON 格式无效或存在重复字段");
        }
    }

    private JsonNode parse(String raw) { return parse(raw.getBytes(StandardCharsets.UTF_8)); }

    private void parseFields(Map<String,Object> row, String... fields) {
        for (String field : fields) {
            Object value = row.get(field);
            if (value != null) row.put(field, parse(value.toString()));
        }
    }

    String hash(Object value) { return digest(json(value).getBytes(StandardCharsets.UTF_8)); }

    static String digest(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }

    static Long id(Map<String,Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number)value).longValue();
    }

    static long number(JsonNode input, String field) {
        Long value = optionalNumber(input, field);
        if (value == null) throw invalid("请提供 " + field);
        return value;
    }

    static Long optionalNumber(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalid("无效的 " + field);
        return value.asLong();
    }

    static String text(JsonNode input, String field, int max, boolean required) {
        JsonNode value = input.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) throw invalid(field + " 必须为文本");
        String text = value == null || value.isNull() ? "" : value.asText().trim();
        if (text.length() > max) throw invalid(field + " 超过最大长度 " + max);
        if (required && text.isEmpty()) throw invalid("请填写 " + field);
        return text.isEmpty() ? null : text;
    }

    private static String code(JsonNode item, String field) {
        String code = text(item, field, 64, true);
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw invalid("无效代码：" + code);
        return code;
    }

    private static List<String> strings(JsonNode item, String field, int max) {
        JsonNode value = item.get(field);
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isNull()) return new ArrayList<>();
        if (!value.isArray() || value.size() > 1000) throw invalid(field + " 必须为字符串数组");
        for (JsonNode part : value) {
            if (!part.isTextual() || part.asText().trim().isEmpty() || part.asText().trim().length() > max)
                throw invalid(field + " 存在空值或超长字段");
            if (!result.add(part.asText().trim()) && "members".equals(field)) throw invalid("簇内学科代码重复");
        }
        return new ArrayList<>(result);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> list(Map<String,Object> data, String name) {
        return (List<Map<String,Object>>)data.get(name);
    }

    static boolean truth(Object value) { return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number)value).intValue() != 0); }
    private static String camel(String field) {
        String[] parts = field.split("_");
        StringBuilder key = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        return key.toString();
    }
    static IdentityException invalid(String message) { return new IdentityException(400, message); }
    static IdentityException conflict(String message) { return new IdentityException(409, message); }
    static IdentityException denied() { return new IdentityException(403, "没有所需的知识库操作权限"); }
    static IdentityException missing() { return new IdentityException(404, "知识库或资源不存在"); }
}
