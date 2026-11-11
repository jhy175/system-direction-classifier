package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.dao.IdentityDAO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Component
@Profile("mysql")
final class RecognitionStore {
    static final String RUN = "demo_recognition_run";
    static final String STAGE = "demo_run_stage";
    static final String CALL = "demo_model_call";
    static final String HISTORY = "demo_debug_record";
    private static final String RUN_QUERY =
        "SELECT r.id,r.created_by,r.input_snapshot_id,r.method_version_id,r.knowledge_version_id,r.parent_run_id," +
        "r.trigger_type,r.status,r.start_stage_code,r.failed_stage_code,r.error_code,r.error_message,r.current_result_id," +
        "r.implementation_build,r.resource_manifest_sha256,r.execution_overrides_json,r.created_at,r.started_at,r.finished_at," +
        "m.id AS method_id,m.name AS method_name,v.version_no AS method_version_no,kb.name AS knowledge_name," +
        "kv.version_no AS knowledge_version_no,i.raw_input_json " +
        "FROM demo_recognition_run r JOIN demo_input_snapshot i ON i.id=r.input_snapshot_id " +
        "JOIN demo_method_version v ON v.id=r.method_version_id JOIN demo_method m ON m.id=v.method_id " +
        "JOIN demo_knowledge_version kv ON kv.id=r.knowledge_version_id JOIN demo_knowledge_base kb ON kb.id=kv.knowledge_base_id ";
    final IdentityDAO db;
    final ObjectMapper json;
    final TransactionTemplate tx;

    RecognitionStore(IdentityDAO db, ObjectMapper json, org.springframework.transaction.PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    Map<String,Object> ownedRun(long runId, long actor) {
        Map<String,Object> run = db.one(RUN_QUERY + "WHERE r.id=? AND r.created_by=? AND r.batch_item_id IS NULL", runId, actor);
        if (run == null) throw missing();
        return run;
    }

    Map<String,Object> ownedBatchRun(long runId, long actor) {
        Map<String,Object> run = db.one(RUN_QUERY + "JOIN demo_batch_item b ON b.id=r.batch_item_id " +
            "WHERE r.id=? AND r.created_by=?", runId, actor);
        if (run == null) throw missing();
        return run;
    }

    long snapshot(JsonNode paper, long actor, Long sourceRow) {
        String encoded = paper.toString();
        String year = paper.path("year").asText();
        return db.insert("demo_input_snapshot", fields("created_by", actor, "source_batch_row_id", sourceRow,
            "content_type", paper.path("type").asText("学术论文"), "title", paper.path("title").asText(),
            "keywords", paper.path("keywords").asText(), "abstract_text", paper.path("abstract").asText(),
            "journal_name", paper.path("journal").asText(), "doi", paper.path("doi").asText(), "publication_raw", year,
            "publication_year", year.matches("[0-9]{4}") ? Integer.valueOf(year) : null,
            "external_record_id", paper.path("externalId").asText(), "raw_input_json", encoded, "content_sha256", hash(encoded)));
    }

    void initializeStages(long run, List<Map<String,Object>> definitions, Map<String,Long> reused) {
        for (Map<String,Object> definition : definitions) {
            String code = (String)definition.get("stageCode");
            Long source = reused.get(code);
            db.insert(STAGE, fields("run_id", run, "stage_code", code, "source_stage_id", source,
                "status", source == null ? "pending" : "reused", "payload_schema_version", definition.get("outputSchemaVersion")));
        }
    }

    Map<String,Object> stage(long runId, String code) {
        Map<String,Object> row = db.one("SELECT * FROM demo_run_stage WHERE run_id=? AND stage_code=?", runId, code);
        if (row == null) throw missing();
        return row;
    }

    Map<String,Object> effectiveStage(Map<String,Object> stage) {
        Long id = id(stage, "sourceStageId");
        return id == null ? stage : db.find(STAGE, id, false);
    }

    Map<String,Object> view(Map<String,Object> run, Long resultId) {
        long runId = id(run, "id");
        Map<String,Object> result = new LinkedHashMap<>(run);
        result.put("input", node(result.remove("rawInputJson")));
        result.put("overrides", node(result.remove("executionOverridesJson")));
        result.remove("createdBy");
        result.remove("inputSnapshotId");
        result.put("stages", db.query("SELECT stage_code,status,source_stage_id,error_code,error_message,duration_ms " +
            "FROM demo_run_stage WHERE run_id=? ORDER BY id", runId));
        result.put("result", resultId == null ? null : result(runId, resultId));
        return result;
    }

    private Map<String,Object> result(long runId, long resultId) {
        Map<String,Object> result = db.one("SELECT id,revision_no,summary,created_at,result_sha256 FROM demo_run_result " +
            "WHERE run_id=? AND id=?", runId, resultId);
        result.put("disciplines", db.query("SELECT p.code_snapshot AS code,p.name_snapshot AS name,d.final_rank,d.final_level," +
            "c.recall_rank,c.recall_level,j.raw_level,j.reason,j.confidence FROM demo_result_discipline d " +
            "JOIN demo_knowledge_discipline p ON p.id=d.knowledge_discipline_id JOIN demo_run_candidate c ON c.id=d.candidate_id " +
            "JOIN demo_candidate_judgment j ON j.candidate_id=c.id WHERE d.result_id=? ORDER BY d.final_rank", resultId));
        result.put("metrics", db.query("SELECT metric_code,metric_value,status,source_name,source_method,source_version,generated_at " +
            "FROM demo_result_metric WHERE result_id=? ORDER BY metric_code", resultId));
        return result;
    }

    Map<String,Object> stageView(long runId, String code, boolean calls) {
        Map<String,Object> own = stage(runId, code);
        Map<String,Object> effective = effectiveStage(own);
        own.put("input", node(effective.get("inputJson")));
        own.put("output", node(effective.get("outputJson")));
        own.remove("inputJson");
        own.remove("outputJson");
        if (id(own, "sourceStageId") != null) own.put("sourceRunId", effective.get("runId"));
        if (calls) {
            List<Map<String,Object>> evidence = db.query("SELECT c.id,c.logical_call_no,c.attempt_no,c.model_connection_revision_id," +
                "c.endpoint_snapshot,c.request_json,c.raw_response,c.parsed_response_json,c.provider_request_id,c.usage_json," +
                "c.status,c.cache_hit,c.error_code,c.error_message,c.duration_ms,c.started_at,c.finished_at,p.code_snapshot AS discipline_code," +
                "p.name_snapshot AS discipline_name,r.model_id FROM demo_model_call c " +
                "JOIN demo_model_connection_revision r ON r.id=c.model_connection_revision_id " +
                "LEFT JOIN demo_run_candidate a ON a.id=c.candidate_id LEFT JOIN demo_knowledge_discipline p ON p.id=a.knowledge_discipline_id " +
                "WHERE c.stage_id=? ORDER BY a.recall_rank,c.logical_call_no,c.attempt_no,c.id", id(effective, "id"));
            for (Map<String,Object> call : evidence) {
                call.put("request", node(call.remove("requestJson")));
                call.put("parsedResponse", node(call.remove("parsedResponseJson")));
                call.put("usage", node(call.remove("usageJson")));
            }
            own.put("calls", evidence);
        }
        return own;
    }

    Map<String,Object> historyRecord(long historyId, long actor) {
        Map<String,Object> row = db.one("SELECT id,run_id,result_id,name,saved_at FROM demo_debug_record WHERE id=? AND saved_by=?",
            historyId, actor);
        if (row == null) throw missing();
        return row;
    }

    Map<String,Object> history(long actor, String search, int page) {
        String query =
            " FROM demo_debug_record h JOIN demo_recognition_run r ON r.id=h.run_id " +
            "JOIN demo_input_snapshot i ON i.id=r.input_snapshot_id JOIN demo_method_version v ON v.id=r.method_version_id " +
            "JOIN demo_method m ON m.id=v.method_id JOIN demo_knowledge_version kv ON kv.id=r.knowledge_version_id " +
            "JOIN demo_knowledge_base kb ON kb.id=kv.knowledge_base_id JOIN demo_user u ON u.id=r.created_by " +
            "LEFT JOIN demo_run_result s ON s.id=h.result_id LEFT JOIN demo_knowledge_discipline d ON d.id=s.main_knowledge_discipline_id " +
            "LEFT JOIN demo_result_metric x ON x.result_id=s.id AND x.metric_code='ICI' " +
            "WHERE h.saved_by=? AND r.created_by=? AND (LOCATE(?,COALESCE(i.title,''))>0 OR LOCATE(?,m.name)>0)";
        long total = db.count("SELECT COUNT(*)" + query, actor, actor, search, search);
        List<Map<String,Object>> items = db.query("SELECT h.id,h.run_id,h.saved_at,i.title,m.name AS method_name,v.version_no AS method_version_no," +
            "kb.name AS knowledge_name,kv.version_no AS knowledge_version_no,r.status,u.display_name AS actor_name," +
            "d.name_snapshot AS main_discipline,x.metric_value,x.status AS metric_status," +
            "JSON_LENGTH(JSON_EXTRACT(r.execution_overrides_json,'$.manualDisciplineIds')) AS manual_count" + query +
            " ORDER BY h.saved_at DESC,h.id DESC LIMIT 20 OFFSET ?", actor, actor, search, search, (page - 1) * 20);
        return fields("items", items, "total", total, "page", page, "pageSize", 20);
    }

    JsonNode node(Object value) {
        if (value instanceof JsonNode) return (JsonNode)value;
        if (value == null) return json.nullNode();
        try { return json.readTree(value.toString()); }
        catch (IOException e) { throw new IllegalStateException("Invalid stored recognition JSON", e); }
    }

    static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put((String)pairs[i], pairs[i + 1]);
        return row;
    }

    static Long id(Map<String,Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number)value).longValue() : null;
    }

    static Timestamp now() { return Timestamp.from(Instant.now()); }

    static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte part : bytes) hex.append(String.format(Locale.ROOT, "%02x", part & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static IdentityException missing() { return new IdentityException(404, "调试记录或阶段不存在"); }
    static IdentityException invalid(String message) { return new IdentityException(400, message); }
    static IdentityException conflict(String message) { return new IdentityException(409, message); }
    static boolean terminal(Map<String,Object> run) {
        return "failed".equals(run.get("status")) || "succeeded".equals(run.get("status"));
    }
}
