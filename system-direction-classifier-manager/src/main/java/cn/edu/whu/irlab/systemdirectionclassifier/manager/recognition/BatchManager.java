package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.CurrentActor;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.identity.IdentityManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.knowledge.KnowledgeFiles;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.method.MethodManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.RecognitionStore.*;

@Component
@Profile("mysql")
public class BatchManager {
    private static final String TASK = "demo_batch_task";
    private static final String IMPORT = "demo_batch_import";
    private static final String ROW = "demo_batch_row";
    private static final String ITEM = "demo_batch_item";
    private static final List<String> STATUSES = Arrays.asList("", "queued", "running", "succeeded", "failed", "partial_failed");
    private static final List<String> ITEM_STATUSES = Arrays.asList("", "pending", "running", "succeeded", "failed");
    private static final String TASK_QUERY =
        "SELECT t.id,t.name,t.status,t.created_at,t.submitted_at,t.started_at,t.finished_at,t.selected_import_id," +
        "t.method_version_id,t.knowledge_version_id,v.method_id,m.name AS method_name,v.version_no AS method_version_no," +
        "kv.version_no AS knowledge_version_no,k.name AS knowledge_name,bi.sheet_name,bi.validation_summary_json," +
        "bi.validation_rules_json,a.original_name,u.display_name AS submitted_by " +
        "FROM demo_batch_task t JOIN demo_method_version v ON v.id=t.method_version_id JOIN demo_method m ON m.id=v.method_id " +
        "JOIN demo_knowledge_version kv ON kv.id=t.knowledge_version_id JOIN demo_knowledge_base k ON k.id=kv.knowledge_base_id " +
        "JOIN demo_user u ON u.id=t.created_by LEFT JOIN demo_batch_import bi ON bi.id=t.selected_import_id " +
        "LEFT JOIN demo_file_asset a ON a.id=bi.source_asset_id ";
    private static final String ITEM_QUERY =
        " FROM demo_batch_item b JOIN demo_batch_row br ON br.id=b.batch_row_id " +
        "JOIN demo_input_snapshot i ON i.id=b.input_snapshot_id " +
        "LEFT JOIN demo_recognition_run r ON r.id=b.current_run_id LEFT JOIN demo_run_result s ON s.id=b.adopted_result_id " +
        "LEFT JOIN demo_knowledge_discipline d ON d.id=s.main_knowledge_discipline_id " +
        "LEFT JOIN demo_result_metric x ON x.result_id=s.id AND x.metric_code='ICI' ";
    private static final String ITEM_COLUMNS =
        "SELECT b.id,b.status,b.current_run_id,b.adopted_run_id,b.adopted_result_id,br.source_row_no,br.external_record_id," +
        "br.business_key,i.title,s.revision_no,d.code_snapshot AS main_code,d.name_snapshot AS main_discipline," +
        "x.metric_value,x.status AS metric_status,r.error_code,r.error_message,r.failed_stage_code";
    private static final String[] INPUT_FIELDS = {"title","keywords","abstract","journal","doi","year","externalId","text"};
    private static final int[] INPUT_LIMITS = {16000,16000,120000,512,512,255,255,120000};

    private final RecognitionStore store;
    private final IdentityManager identity;
    private final MethodManager methods;
    private final ModelConnectionManager models;
    private final KnowledgeFiles assets;

    BatchManager(RecognitionStore store, IdentityManager identity, MethodManager methods,
                 ModelConnectionManager models, KnowledgeFiles assets) {
        this.store = store; this.identity = identity; this.methods = methods; this.models = models; this.assets = assets;
    }

    public Map<String,Object> create(JsonNode request) {
        long actor = access("batch:create");
        String name = text(request, "name", 255);
        if (name.isEmpty()) throw invalid("请填写任务名称");
        Map<String,Object> basis = executable(requiredId(request, "methodId"), requiredId(request, "methodVersionId"));
        long task = store.db.insert(TASK, fields("name", name, "created_by", actor, "method_version_id", id(basis, "id"),
            "knowledge_version_id", id(basis, "knowledgeVersionId")));
        return taskView(task, actor);
    }

    public Map<String,Object> inspect(long task, JsonNode request) {
        long actor = access("batch:create");
        draft(task, actor, false);
        BatchInputFile file = inputFile(request);
        return fileView(file);
    }

    public Map<String,Object> check(long task, JsonNode request) {
        long actor = access("batch:create");
        draft(task, actor, false);
        BatchInputFile file = inputFile(request);
        if (file.rows.isEmpty()) throw invalid("所选文件或工作表没有论文数据");
        JsonNode mapping = request.path("mapping");
        if (!mapping.isObject()) throw invalid("请选择输入字段映射");
        Map<String,Integer> fields = new LinkedHashMap<>();
        for (String key : INPUT_FIELDS) {
            String column = text(mapping, key, 255);
            if (!column.isEmpty() && !file.columns.contains(column)) throw invalid("所选列不存在：" + column);
            fields.put(key, file.columns.indexOf(column));
        }
        if (Arrays.asList("title","keywords","abstract","text").stream().noneMatch(key -> fields.get(key) >= 0)) {
            throw invalid("至少映射标题、关键词、摘要或拼接原文中的一列");
        }

        JsonNode business = request.path("businessKeyColumns");
        if (!business.isArray() || business.size() > 3) throw invalid("业务键支持最多三列");
        Set<Integer> businessColumns = new LinkedHashSet<>();
        for (JsonNode key : business) {
            int index = key.isTextual() ? file.columns.indexOf(key.asText()) : -1;
            if (index < 0 || !businessColumns.add(index)) throw invalid("业务键包含不存在或重复的列");
        }
        List<CheckedRow> checked = new ArrayList<>();
        Map<String,Integer> keyCounts = new HashMap<>();
        for (BatchInputFile.Row row : file.rows) {
            CheckedRow entry = new CheckedRow(row);
            for (int i = 0; i < INPUT_FIELDS.length; i++) {
                String value = row.value(fields.get(INPUT_FIELDS[i]));
                if (value.length() > INPUT_LIMITS[i]) entry.issues.add("字段过长：" + INPUT_FIELDS[i]);
                entry.input.put(INPUT_FIELDS[i], value);
            }
            if (Arrays.asList("title","keywords","abstract","text").stream().allMatch(key -> entry.input.path(key).asText().trim().isEmpty())) {
                entry.issues.add("缺少识别文本");
            }
            if (row.cells.size() > file.columns.size()) entry.issues.add("该行的列数超过表头");
            if (!businessColumns.isEmpty()) {
                ArrayNode key = store.json.createArrayNode();
                for (int column : businessColumns) key.add(row.value(column).trim());
                if (businessColumns.stream().anyMatch(column -> row.value(column).trim().isEmpty())) {
                    entry.issues.add("业务键缺少值");
                } else {
                    String businessKey = key.toString();
                    if (businessKey.length() > 512) entry.issues.add("业务键过长");
                    else {
                        entry.businessKey = businessKey;
                        keyCounts.merge(businessKey, 1, Integer::sum);
                    }
                }
            }
            checked.add(entry);
        }
        for (CheckedRow entry : checked) {
            if (keyCounts.getOrDefault(entry.businessKey, 0) > 1) entry.issues.add("业务键重复");
        }
        long valid = checked.stream().filter(row -> row.issues.isEmpty()).count();
        Map<String,Object> summary = fields("total", checked.size(), "valid", valid, "invalid", checked.size() - valid);
        long importId = store.tx.execute(tx -> {
            draft(task, actor, true);
            long asset = assets.storeImport(file.name, file.content, actor);
            long imported = store.db.insert(IMPORT, fields("task_id", task, "source_asset_id", asset,
                "sheet_name", file.sheet, "file_encoding", file.encoding, "field_mapping_json", mapping.toString(),
                "validation_rules_json", store.json.valueToTree(fields("fileName", file.name, "columns", file.columns,
                    "businessKeyColumns", business)).toString(),
                "validation_summary_json", store.json.valueToTree(summary).toString(), "status", "checked", "checked_at", now()));
            assets.reference(asset, "batch_import", imported, "source");
            for (CheckedRow entry : checked) {
                String externalId = entry.input.path("externalId").asText();
                store.db.insert(ROW, fields("import_id", imported, "source_row_no", entry.source.number, "external_record_id",
                    externalId.length() > 255 ? null : externalId, "business_key", entry.businessKey,
                    "raw_row_json", store.json.valueToTree(entry.source.cells).toString(), "mapped_input_json", entry.input.toString(),
                    "issues_json", store.json.valueToTree(entry.issues).toString(),
                    "validation_status", entry.issues.isEmpty() ? "valid" : "invalid"));
            }
            return imported;
        });
        List<Map<String,Object>> preview = new ArrayList<>();
        for (CheckedRow entry : checked.subList(0, Math.min(checked.size(), 20))) {
            preview.add(fields("sourceRowNo", entry.source.number, "input", entry.input, "issues", entry.issues));
        }
        return fields("importId", importId, "summary", summary, "rows", preview);
    }

    public Map<String,Object> submit(long task, JsonNode request) {
        long actor = access("batch:create");
        long importId = requiredId(request, "importId");
        String key = text(request, "submitKey", 128);
        if (key.isEmpty()) throw invalid("缺少提交标识");
        if (request.has("skipInvalid") && !request.path("skipInvalid").isBoolean()) throw invalid("跳过选项格式无效");
        boolean skip = request.path("skipInvalid").asBoolean();
        store.tx.execute(tx -> {
            Map<String,Object> current = owner(task, actor, true);
            if (!"draft".equals(current.get("status"))) {
                if (key.equals(current.get("submitKey")) && Objects.equals(id(current, "selectedImportId"), importId)) return null;
                throw conflict("该任务已经提交");
            }
            long methodId = id(store.db.one("SELECT method_id FROM demo_method_version WHERE id=?", id(current, "methodVersionId")), "methodId");
            executable(methodId, id(current, "methodVersionId"));
            if (store.db.one("SELECT id FROM demo_batch_import WHERE task_id=? AND id=? AND status='checked'", task, importId) == null) {
                throw invalid("请选择本任务已完成校验的导入记录");
            }
            List<Map<String,Object>> rows = store.db.query("SELECT id,mapped_input_json,validation_status FROM demo_batch_row WHERE import_id=? ORDER BY source_row_no", importId);
            long valid = rows.stream().filter(row -> "valid".equals(row.get("validationStatus"))).count();
            if (valid == 0) throw invalid("没有可执行的论文");
            if (!skip && valid < rows.size()) throw conflict("请修正异常行，或明确选择跳过异常行");
            store.db.execute("UPDATE demo_batch_task SET selected_import_id=?,submit_key=?,status='queued',submitted_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                importId, key, task);
            if (skip) store.db.execute("UPDATE demo_batch_row SET skipped_at=CURRENT_TIMESTAMP(6) WHERE import_id=? AND validation_status='invalid'", importId);
            for (Map<String,Object> row : rows) {
                if (!"valid".equals(row.get("validationStatus"))) continue;
                long rowId = id(row, "id");
                long snapshot = store.snapshot(store.node(row.get("mappedInputJson")), actor, rowId);
                store.db.insert(ITEM, fields("task_id", task, "import_id", importId, "batch_row_id", rowId, "input_snapshot_id", snapshot));
            }
            return null;
        });
        return taskView(task, actor);
    }

    public Map<String,Object> list(String search, String status, int page) {
        long actor = access("batch:read");
        page(page);
        if (search.length() > 255 || !STATUSES.contains(status)) throw invalid("无效的任务筛选条件");
        String where = "WHERE t.created_by=? AND t.status<>'draft' AND LOCATE(?,t.name)>0 AND (?='' OR t.status=?)";
        long total = store.db.count("SELECT COUNT(*) FROM demo_batch_task t " + where, actor, search.trim(), status, status);
        List<Map<String,Object>> rows = store.db.query(TASK_QUERY + where + " ORDER BY t.submitted_at DESC,t.id DESC LIMIT 20 OFFSET ?",
            actor, search.trim(), status, status, (page - 1) * 20);
        for (Map<String,Object> row : rows) decorate(row);
        return fields("items", rows, "total", total, "page", page, "pageSize", 20);
    }

    public Map<String,Object> detail(long task, String status, String discipline, int page) {
        long actor = access("batch:read");
        page(page);
        if (!ITEM_STATUSES.contains(status) || discipline.length() > 64) throw invalid("无效的结果筛选条件");
        Map<String,Object> result = taskView(task, actor);
        String where = "WHERE b.task_id=? AND (?='' OR b.status=?) AND (?='' OR EXISTS (" +
            "SELECT 1 FROM demo_result_discipline rd JOIN demo_knowledge_discipline pd ON pd.id=rd.knowledge_discipline_id " +
            "WHERE rd.result_id=b.adopted_result_id AND pd.code_snapshot=?))";
        long count = store.db.count("SELECT COUNT(*)" + ITEM_QUERY + where, task, status, status, discipline, discipline);
        List<Map<String,Object>> items = store.db.query(ITEM_COLUMNS + ITEM_QUERY + where +
            " ORDER BY br.source_row_no,b.id LIMIT 20 OFFSET ?", task, status, status, discipline, discipline, (page - 1) * 20);
        for (Map<String,Object> item : items) item.put("disciplines", disciplines(id(item, "adoptedResultId")));
        result.put("results", fields("items", items, "total", count, "page", page, "pageSize", 20));
        result.put("disciplineOptions", store.db.query("SELECT DISTINCT d.code_snapshot AS code,d.name_snapshot AS name " +
            "FROM demo_batch_item b JOIN demo_result_discipline rd ON rd.result_id=b.adopted_result_id " +
            "JOIN demo_knowledge_discipline d ON d.id=rd.knowledge_discipline_id WHERE b.task_id=? ORDER BY code", task));
        return result;
    }

    public Map<String,Object> item(long task, long item, Long run) {
        long actor = access("batch:read");
        owner(task, actor, false);
        Map<String,Object> row = item(task, item);
        Long chosen = run == null ? id(row, "currentRunId") : run;
        Map<String,Object> view = null;
        if (chosen != null) {
            validateRun(task, item, chosen);
            Map<String,Object> record = store.ownedBatchRun(chosen, actor);
            Long result = Objects.equals(chosen, id(row, "adoptedRunId")) ? id(row, "adoptedResultId") : id(record, "currentResultId");
            view = store.view(record, result);
        }
        List<Map<String,Object>> attempts = store.db.query("SELECT id,batch_attempt_no,status,failed_stage_code,error_code,error_message,started_at,finished_at " +
            "FROM demo_recognition_run WHERE batch_item_id=? ORDER BY batch_attempt_no DESC", item);
        return fields("item", row, "run", view, "attempts", attempts);
    }

    public Map<String,Object> stage(long task, long item, long run, String stage, boolean calls) {
        owner(task, access("batch:read"), false);
        validateRun(task, item, run);
        return store.stageView(run, stage, calls);
    }

    public Map<String,Object> retry(long task) {
        long actor = access("batch:retry");
        store.tx.execute(tx -> {
            Map<String,Object> row = owner(task, actor, true);
            if (!Arrays.asList("failed","partial_failed").contains(row.get("status"))) throw conflict("请等待任务结束后重试失败项");
            long version = id(row, "methodVersionId");
            long method = id(store.db.one("SELECT method_id FROM demo_method_version WHERE id=?", version), "methodId");
            executable(method, version);
            int changed = store.db.execute("UPDATE demo_batch_item SET status='pending' WHERE task_id=? AND status='failed'", task);
            if (changed == 0) throw conflict("没有可重试的失败项");
            store.db.execute("UPDATE demo_batch_task SET status='queued',finished_at=NULL WHERE id=?", task);
            return null;
        });
        return taskView(task, actor);
    }

    public byte[] export(long task, String kind, Long requestedImport) {
        long actor = access("batch:export");
        return store.tx.execute(tx -> {
            Map<String,Object> row = owner(task, actor, false);
            StringBuilder csv = new StringBuilder("\uFEFF");
            if ("invalid".equals(kind)) {
                Long imported = requestedImport == null ? id(row, "selectedImportId") : requestedImport;
                if (imported == null || store.db.one("SELECT id FROM demo_batch_import WHERE id=? AND task_id=?", imported, task) == null) {
                    throw invalid("没有可下载的导入记录");
                }
                write(csv, "源行号", "外部编号", "问题", "跳过", "原始列值");
                for (Map<String,Object> invalid : store.db.query("SELECT source_row_no,external_record_id,issues_json,skipped_at,raw_row_json " +
                    "FROM demo_batch_row WHERE import_id=? AND validation_status='invalid' ORDER BY source_row_no", imported)) {
                    write(csv, invalid.get("sourceRowNo"), invalid.get("externalRecordId"), invalid.get("issuesJson"),
                        invalid.get("skippedAt") != null ? "是" : "否", invalid.get("rawRowJson"));
                }
            } else if ("results".equals(kind) || "failed".equals(kind)) {
                boolean successes = "results".equals(kind);
                if (successes) write(csv, "源行号", "外部编号", "标题", "主学科代码", "主学科", "入选学科", "交叉度", "指标状态", "运行ID", "结果修订ID");
                else write(csv, "源行号", "外部编号", "标题", "失败阶段", "错误代码", "错误", "运行ID");
                for (Map<String,Object> item : store.db.query(ITEM_COLUMNS + ITEM_QUERY + "WHERE b.task_id=? AND b.status=? ORDER BY br.source_row_no",
                    task, successes ? "succeeded" : "failed")) {
                    if (successes) write(csv, item.get("sourceRowNo"), item.get("externalRecordId"), item.get("title"), item.get("mainCode"),
                        item.get("mainDiscipline"), store.json.valueToTree(disciplines(id(item, "adoptedResultId"))).toString(),
                        item.get("metricValue"), Objects.toString(item.get("metricStatus"), "not_provided"),
                        item.get("adoptedRunId"), item.get("adoptedResultId"));
                    else write(csv, item.get("sourceRowNo"), item.get("externalRecordId"), item.get("title"), item.get("failedStageCode"),
                        item.get("errorCode"), item.get("errorMessage"), item.get("currentRunId"));
                }
            } else throw invalid("不支持的导出类型");
            return csv.toString().getBytes(StandardCharsets.UTF_8);
        });
    }

    private static void write(StringBuilder csv, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) csv.append(',');
            String value = Objects.toString(cells[i], "");
            String trimmed = value.trim();
            if (!(cells[i] instanceof Number) && !trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
            csv.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private Map<String,Object> owner(long task, long actor, boolean lock) {
        Map<String,Object> row = store.db.one("SELECT * FROM demo_batch_task WHERE id=? AND created_by=?" + (lock ? " FOR UPDATE" : ""), task, actor);
        if (row == null) throw new IdentityException(404, "批量任务不存在或不可见");
        return row;
    }

    private void draft(long task, long actor, boolean lock) {
        if (!"draft".equals(owner(task, actor, lock).get("status"))) throw conflict("提交后的任务固定导入记录");
    }

    private Map<String,Object> taskView(long task, long actor) {
        Map<String,Object> row = store.db.one(TASK_QUERY + "WHERE t.id=? AND t.created_by=?", task, actor);
        if (row == null) throw new IdentityException(404, "批量任务不存在或不可见");
        decorate(row);
        return row;
    }

    private void decorate(Map<String,Object> row) {
        long task = id(row, "id");
        JsonNode rules = store.node(row.remove("validationRulesJson"));
        row.put("fileName", rules.path("fileName").asText((String)row.remove("originalName")));
        row.put("importSummary", store.node(row.remove("validationSummaryJson")));
        Map<String,Object> counts = store.db.one("SELECT COUNT(*) AS total,COUNT(CASE WHEN status='pending' THEN 1 END) AS pending," +
            "COUNT(CASE WHEN status='running' THEN 1 END) AS running,COUNT(CASE WHEN status='succeeded' THEN 1 END) AS succeeded," +
            "COUNT(CASE WHEN status='failed' THEN 1 END) AS failed FROM demo_batch_item WHERE task_id=?", task);
        row.put("progress", counts);
    }

    private Map<String,Object> item(long task, long item) {
        Map<String,Object> row = store.db.one(ITEM_COLUMNS + ITEM_QUERY + "WHERE b.task_id=? AND b.id=?", task, item);
        if (row == null) throw new IdentityException(404, "论文条目不存在");
        return row;
    }

    private void validateRun(long task, long item, long run) {
        if (store.db.one("SELECT r.id FROM demo_recognition_run r JOIN demo_batch_item b ON b.id=r.batch_item_id " +
            "WHERE b.task_id=? AND b.id=? AND r.id=?", task, item, run) == null) throw missing();
    }

    private List<Map<String,Object>> disciplines(Long result) {
        if (result == null) return Collections.emptyList();
        return store.db.query("SELECT d.code_snapshot AS code,d.name_snapshot AS name,r.final_rank,r.final_level " +
            "FROM demo_result_discipline r JOIN demo_knowledge_discipline d ON d.id=r.knowledge_discipline_id " +
            "WHERE r.result_id=? ORDER BY r.final_rank", result);
    }

    private Map<String,Object> executable(long method, long version) {
        Map<String,Object> basis = methods.batchExecutionVersion(method, version);
        Set<Long> checked = new HashSet<>();
        for (Map<String,Object> stage : RecognitionExecutor.definitions(basis)) {
            if (!Arrays.asList("router","recall","judge").contains(stage.get("stageCode"))) continue;
            Long revision = id(stage, "modelRevisionId");
            if (revision == null) throw conflict("所选方法缺少模型绑定");
            if (checked.add(revision)) models.checkRecognitionModel(revision);
        }
        return basis;
    }

    private Map<String,Object> fileView(BatchInputFile file) {
        List<Map<String,Object>> preview = new ArrayList<>();
        for (BatchInputFile.Row row : file.rows.subList(0, Math.min(10, file.rows.size()))) {
            preview.add(fields("sourceRowNo", row.number, "cells", row.cells));
        }
        return fields("name", file.name, "sheet", file.sheet, "sheets", file.sheets, "encoding", file.encoding,
            "columns", file.columns, "total", file.rows.size(), "rows", preview);
    }

    private static BatchInputFile inputFile(JsonNode request) {
        String name = text(request, "fileName", 255);
        String encoded = text(request, "content", (BatchInputFile.MAX_BYTES + 2) / 3 * 4);
        byte[] content;
        try { content = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw invalid("文件内容格式错误"); }
        if (content.length == 0 || content.length > BatchInputFile.MAX_BYTES) throw invalid("请上传 8 MiB 以内的非空文件");
        return BatchInputFile.read(name, content, text(request, "encoding", 40), text(request, "sheet", 255));
    }

    private long access(String permission) {
        long actor = CurrentActor.id();
        Set<String> permissions = new HashSet<>();
        identity.effectivePermissions(actor).forEach(row -> permissions.add((String)row.get("code")));
        if (!permissions.contains(permission) || !permissions.contains("batch:read")) throw new IdentityException(403, "没有批量识别操作权限");
        return actor;
    }

    private static void page(int page) {
        if (page < 1 || page > 100000) throw invalid("无效页码");
    }

    private static String text(JsonNode input, String key, int limit) {
        JsonNode node = input.path(key);
        if (node.isMissingNode() || node.isNull()) return "";
        if (!node.isTextual() || node.textValue().length() > limit) throw invalid("字段无效或过长：" + key);
        return node.textValue().trim();
    }

    private static long requiredId(JsonNode input, String key) {
        JsonNode value = input.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) throw invalid("请选择有效的 " + key);
        return value.asLong();
    }

    private final class CheckedRow {
        final BatchInputFile.Row source;
        final ObjectNode input = store.json.createObjectNode().put("type", "学术论文");
        final List<String> issues = new ArrayList<>();
        String businessKey;

        CheckedRow(BatchInputFile.Row source) {
            this.source = source;
            if (source.error != null) issues.add(source.error);
        }
    }
}
