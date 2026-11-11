package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.CurrentActor;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.identity.IdentityManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.method.MethodManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.RecognitionStore.*;

@Component
@Profile("mysql")
public class RecognitionManager {
    private final RecognitionStore store;
    private final RecognitionExecutor executor;
    private final MethodManager methods;
    private final ModelConnectionManager models;
    private final IdentityManager identity;
    private final ExecutorService runs = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(12));

    RecognitionManager(RecognitionStore store, RecognitionExecutor executor, MethodManager methods,
                       ModelConnectionManager models, IdentityManager identity) {
        this.store = store;
        this.executor = executor;
        this.methods = methods;
        this.models = models;
        this.identity = identity;
    }

    @PreDestroy
    void close() {
        runs.shutdown();
        try {
            if (!runs.awaitTermination(3, TimeUnit.SECONDS)) {
                for (Runnable pending : runs.shutdownNow()) ((RunJob)pending).stop();
            }
        } catch (InterruptedException e) {
            for (Runnable pending : runs.shutdownNow()) ((RunJob)pending).stop();
            Thread.currentThread().interrupt();
        }
    }

    public Map<String,Object> start(JsonNode request) {
        long actor = access("debug:run");
        Map<String,Object> version = executable(requiredId(request, "methodId"), requiredId(request, "methodVersionId"));
        ObjectNode paper = paper(request.path("input"));
        if (Arrays.asList("title", "abstract", "keywords", "text").stream().allMatch(key -> paper.path(key).asText().trim().isEmpty())) {
            throw invalid("请输入论文标题、关键词或摘要");
        }
        Long sourceRun = request.path("sourceBatchRunId").isMissingNode() ? null : requiredId(request, "sourceBatchRunId");
        Long sourceRow = null;
        if (sourceRun != null) {
            access("batch:read");
            Map<String,Object> source = store.ownedBatchRun(sourceRun, actor);
            sourceRow = id(store.db.one("SELECT source_batch_row_id FROM demo_input_snapshot WHERE id=?",
                id(source, "inputSnapshotId")), "sourceBatchRowId");
        }
        final Long row = sourceRow;
        long runId = store.tx.execute(tx -> {
            long snapshot = store.snapshot(paper, actor, row);
            return create(version, actor, snapshot, sourceRun, sourceRun == null ? "single" : "copy_input",
                "parse", null, Collections.emptyMap());
        });
        return submit(runId, actor);
    }

    public Map<String,Object> branch(long runId, JsonNode request) {
        long actor = access("debug:run");
        Map<String,Object> source = store.ownedRun(runId, actor);
        if (!terminal(source)) throw conflict("请等待当前运行结束");
        String mode = text(request, "mode", 24);
        String stage = text(request, "stage", 32);
        String trigger;
        switch (mode) {
            case "full": stage = "parse"; trigger = "rerun"; break;
            case "retry":
                if (!"failed".equals(source.get("status")) ||
                    !Objects.equals(source.get("failedStageCode"), stage)) throw conflict("请选择实际失败的阶段重试");
                trigger = "stage_retry";
                break;
            case "resume": trigger = "resume"; break;
            case "add_candidate": stage = "judge"; trigger = "intervention"; break;
            default: throw invalid("不支持的调试操作");
        }
        int start = RecognitionAlgorithm.STAGES.indexOf(stage);
        if (start < 0) throw invalid("未知运行阶段");
        Map<String,Object> version = executable(id(source, "methodId"), id(source, "methodVersionId"));
        if (start > 0 && (!Objects.equals(version.get("manifestHash"), source.get("resourceManifestSha256")) ||
            !RecognitionAlgorithm.BUILD.equals(source.get("implementationBuild")))) {
            throw conflict("原运行的算法或知识资源依据已变化，请重新运行全部阶段");
        }
        Map<String,Long> reused = new LinkedHashMap<>();
        for (String before : RecognitionAlgorithm.STAGES.subList(0, start)) {
            Map<String,Object> prior = store.stage(runId, before);
            Map<String,Object> effective = store.effectiveStage(prior);
            if (!"succeeded".equals(effective.get("status")) || effective.get("outputJson") == null) {
                throw conflict("前置阶段尚未成功，请从更早的阶段重新运行");
            }
            reused.put(before, id(effective, "id"));
        }

        JsonNode inherited = store.node(source.get("executionOverridesJson"));
        ObjectNode overrides = inherited.isObject() ? ((ObjectNode)inherited).deepCopy() : store.json.createObjectNode();
        Long manualId = null;
        if ("intervention".equals(trigger)) {
            manualId = requiredId(request, "disciplineId");
            Map<String,Object> target = store.db.one("SELECT id FROM demo_knowledge_discipline WHERE id=? AND knowledge_version_id=? " +
                "AND judgment_text IS NOT NULL AND CHAR_LENGTH(TRIM(judgment_text))>0", manualId, id(source, "knowledgeVersionId"));
            if (target == null) throw invalid("请选择当前知识版本内具有精判文本的学科");
            JsonNode recalled = store.node(store.effectiveStage(store.stage(runId, "recall")).get("outputJson")).path("selected_candidates");
            for (JsonNode row : recalled) {
                if (row.path("discipline_id").asLong() == manualId) throw conflict("该学科已在候选列表中");
            }
            ArrayNode manual = overrides.withArray("manualDisciplineIds");
            for (JsonNode row : manual) {
                if (row.asLong() == manualId) throw conflict("该学科已经人工加入");
            }
            manual.add(manualId);
            if (!overrides.has("originalRunId")) overrides.put("originalRunId", runId);
        }
        final String startStage = stage;
        final Long disciplineId = manualId;
        long child = store.tx.execute(tx -> {
            long id = create(version, actor, id(source, "inputSnapshotId"), runId, trigger, startStage, overrides, reused);
            if (disciplineId != null) {
                store.db.insert("demo_run_intervention", fields("source_run_id", runId, "child_run_id", id, "actor_id", actor,
                    "intervention_type", "add_candidate", "target_knowledge_discipline_id", disciplineId, "affected_stage_code", "judge",
                    "change_json", store.json.createObjectNode().put("added_discipline_id", disciplineId).toString()));
            }
            return id;
        });
        return submit(child, actor);
    }

    public Map<String,Object> detail(long runId) {
        Map<String,Object> run = store.ownedRun(runId, readActor());
        return store.view(run, id(run, "currentResultId"));
    }

    public Map<String,Object> stage(long runId, String code, boolean calls) {
        store.ownedRun(runId, readActor());
        return store.stageView(runId, code, calls);
    }

    public List<Map<String,Object>> disciplines(long runId) {
        Map<String,Object> run = store.ownedRun(runId, access("debug:run"));
        return store.db.query("SELECT id,code_snapshot AS code,name_snapshot AS name FROM demo_knowledge_discipline " +
            "WHERE knowledge_version_id=? AND judgment_text IS NOT NULL AND CHAR_LENGTH(TRIM(judgment_text))>0 ORDER BY code_snapshot",
            id(run, "knowledgeVersionId"));
    }

    public Map<String,Object> history(String search, int page) {
        if (search.length() > 255 || page < 1 || page > 100000) throw invalid("无效的历史查询条件");
        return store.history(access("debug:history:read"), search.trim(), page);
    }

    public Map<String,Object> historyDetail(long historyId) {
        long actor = access("debug:history:read");
        Map<String,Object> record = store.historyRecord(historyId, actor);
        Map<String,Object> run = store.ownedRun(id(record, "runId"), actor);
        Map<String,Object> view = store.view(run, id(record, "resultId"));
        view.put("historyId", historyId);
        view.put("savedAt", record.get("savedAt"));
        return view;
    }

    public Map<String,Object> save(long runId) {
        long actor = access("debug:history:read");
        return store.tx.execute(tx -> {
            Map<String,Object> locked = store.db.find(RUN, runId, true);
            if (locked == null || !Objects.equals(id(locked, "createdBy"), actor) || id(locked, "batchItemId") != null) throw missing();
            if (!terminal(locked)) throw conflict("请在运行结束后保存调试历史");
            Long result = id(locked, "currentResultId");
            Map<String,Object> existing = store.db.one("SELECT id FROM demo_debug_record WHERE run_id=? AND saved_by=? AND result_id <=> ?",
                runId, actor, result);
            long historyId = existing == null ? store.db.insert(HISTORY, fields("run_id", runId, "result_id", result, "saved_by", actor))
                : id(existing, "id");
            return store.historyRecord(historyId, actor);
        });
    }

    private Map<String,Object> executable(long methodId, long versionId) {
        Map<String,Object> version = methods.executionVersion(methodId, versionId);
        List<Map<String,Object>> stages = RecognitionExecutor.definitions(version);
        if (stages.size() != RecognitionAlgorithm.STAGES.size() || version.get("manifestHash") == null) {
            throw conflict("所选方法缺少执行阶段或知识版本清单");
        }
        Set<Long> checked = new HashSet<>();
        for (Map<String,Object> stage : stages) {
            if (!Arrays.asList("router", "recall", "judge").contains(stage.get("stageCode"))) continue;
            Long model = id(stage, "modelRevisionId");
            if (model == null) throw conflict("所选方法缺少模型绑定");
            if (checked.add(model)) models.checkRecognitionModel(model);
        }
        return version;
    }

    private long create(Map<String,Object> version, long actor, long snapshot, Long parent, String trigger, String start,
                        ObjectNode overrides, Map<String,Long> reused) {
        long run = store.db.insert(RUN, fields("input_snapshot_id", snapshot, "method_version_id", id(version, "id"),
            "knowledge_version_id", id(version, "knowledgeVersionId"), "created_by", actor, "parent_run_id", parent,
            "trigger_type", trigger, "start_stage_code", start, "implementation_build", RecognitionAlgorithm.BUILD,
            "resource_manifest_sha256", version.get("manifestHash"),
            "execution_overrides_json", overrides == null || overrides.isEmpty() ? null : overrides.toString()));
        store.initializeStages(run, RecognitionExecutor.definitions(version), reused);
        return run;
    }

    private Map<String,Object> submit(long runId, long actor) {
        try { runs.execute(new RunJob(runId, actor)); }
        catch (RejectedExecutionException e) {
            executor.fail(runId, null, new RecognitionExecutor.StageFailure("queue_full", "调试任务较多，请稍后重新运行"));
        }
        Map<String,Object> run = store.ownedRun(runId, actor);
        return store.view(run, id(run, "currentResultId"));
    }

    private ObjectNode paper(JsonNode request) {
        if (!request.isObject()) throw invalid("请提供论文输入");
        ObjectNode result = store.json.createObjectNode();
        String[] keys = {"type","title","keywords","abstract","journal","doi","year","externalId","text"};
        int[] limits = {48, 16000, 16000, 120000, 512, 512, 255, 255, 120000};
        for (int i = 0; i < keys.length; i++) result.put(keys[i], text(request, keys[i], limits[i]));
        return result;
    }

    private static String text(JsonNode request, String key, int limit) {
        JsonNode node = request.path(key);
        if (node.isMissingNode() || node.isNull()) return "";
        if (!node.isTextual() || node.textValue().length() > limit) throw invalid("字段格式无效或过长：" + key);
        return node.textValue();
    }

    private static long requiredId(JsonNode request, String key) {
        JsonNode node = request.path(key);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 1) throw invalid("请选择有效的 " + key);
        return node.asLong();
    }

    private long access(String permission) {
        long actor = CurrentActor.id();
        if (identity.effectivePermissions(actor).stream().noneMatch(row -> permission.equals(row.get("code")))) {
            throw new IdentityException(403, "没有单篇调试操作权限");
        }
        return actor;
    }

    private long readActor() {
        long actor = CurrentActor.id();
        if (identity.effectivePermissions(actor).stream().noneMatch(row ->
            "debug:run".equals(row.get("code")) || "debug:history:read".equals(row.get("code")))) {
            throw new IdentityException(403, "没有查看单篇调试的权限");
        }
        return actor;
    }

    private final class RunJob implements Runnable {
        final long id, actor;
        RunJob(long id, long actor) { this.id = id; this.actor = actor; }
        public void run() { executor.execute(id, actor); }
        void stop() { executor.fail(id, null, new RecognitionExecutor.StageFailure("interrupted", "服务关闭，运行等待重新执行")); }
    }
}
