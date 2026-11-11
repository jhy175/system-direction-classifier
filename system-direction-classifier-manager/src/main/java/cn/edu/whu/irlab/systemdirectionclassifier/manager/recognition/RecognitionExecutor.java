package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.CurrentActor;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.method.MethodManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager.ChatReply;
import cn.edu.whu.irlab.systemdirectionclassifier.manager.model.ModelConnectionManager.RecognitionChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.RecognitionStore.*;

@Component
@Profile("mysql")
final class RecognitionExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(RecognitionExecutor.class);
    private final RecognitionStore store;
    private final MethodManager methods;
    private final ModelConnectionManager models;
    private final RecognitionAlgorithm algorithm;
    private final ExecutorService judges = Executors.newFixedThreadPool(6);
    private final AtomicLong requestSequence = new AtomicLong();

    RecognitionExecutor(RecognitionStore store, MethodManager methods, ModelConnectionManager models) {
        this.store = store;
        this.methods = methods;
        this.models = models;
        this.algorithm = new RecognitionAlgorithm(store.json);
    }

    @PreDestroy
    void close() { judges.shutdownNow(); }

    void execute(long runId, long actor) {
        execute(runId, actor, null);
    }

    void execute(long runId, long actor, Lease lease) {
        CurrentActor.set(actor);
        Map<String,Object> stage = null;
        try {
            if (lease != null) lease.check();
            if (store.db.execute("UPDATE demo_recognition_run SET status='running',started_at=? WHERE id=? AND status='pending'",
                now(), runId) != 1) return;
            Map<String,Object> run = lease == null ? store.ownedRun(runId, actor) : store.ownedBatchRun(runId, actor);
            Map<String,Object> version = lease == null ? methods.executionVersion(id(run, "methodId"), id(run, "methodVersionId"))
                : methods.batchExecutionVersion(id(run, "methodId"), id(run, "methodVersionId"));
            if (!Objects.equals(run.get("resourceManifestSha256"), version.get("manifestHash"))) {
                throw new StageFailure("resource_changed", "运行引用的知识资源版本已变化");
            }
            Work work = new Work(run, version, actor, lease);
            for (String code : RecognitionAlgorithm.STAGES) {
                if (lease != null) lease.check();
                stage = store.stage(runId, code);
                Map<String,Object> config = work.configs.get(code);
                if ("reused".equals(stage.get("status"))) {
                    JsonNode output = store.node(store.effectiveStage(stage).get("outputJson"));
                    work.outputs.put(code, output);
                    if ("judge".equals(code)) restoreJudgments(work, output);
                    continue;
                }
                long stageId = id(stage, "id");
                store.db.update(STAGE, stageId, fields("status", "running", "started_at", now()));
                long started = System.nanoTime();
                ObjectNode output = runStage(work, code, stageId, config);
                if (lease != null) lease.check();
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if ("result".equals(code)) publish(work, output, stageId, duration);
                else finishStage(stageId, output, duration);
                work.outputs.put(code, output);
            }
        } catch (LeaseLost e) {
            LOG.info("Batch run {} no longer holds its claim", runId);
        } catch (RuntimeException e) {
            StageFailure failure;
            if (e instanceof StageFailure) failure = (StageFailure)e;
            else if (e instanceof IdentityException) failure = new StageFailure("dependency_unavailable", e.getMessage());
            else {
                LOG.warn("Recognition run {} failed at {} ({})", runId, stage == null ? "startup" : stage.get("stageCode"),
                    e.getClass().getSimpleName());
                failure = new StageFailure("execution_error", "执行或保存阶段结果时发生错误");
            }
            try { fail(runId, stage, failure, lease); }
            catch (LeaseLost ignored) { /* Recovery already owns this item. */ }
        } finally {
            CurrentActor.clear();
        }
    }

    private ObjectNode runStage(Work work, String code, long stageId, Map<String,Object> definition) {
        JsonNode config = store.node(definition.get("config"));
        switch (code) {
            case "parse":
                input(stageId, work.input);
                return algorithm.parse(work.input, config);
            case "router": {
                String text = work.outputs.get("parse").path("recall_text").asText();
                ArrayNode brief = algorithm.clusterBrief(work.clusters);
                ObjectNode request = store.json.createObjectNode().put("paper_text", text);
                request.set("clusters", brief);
                input(stageId, request);
                JsonNode response = call(work, stageId, null, 1, definition, variables(
                    "paper_text", text, "clusters_json", brief.toString(), "max_clusters", config.path("max_clusters").asText())).response;
                return algorithm.route(response, work.clusters, config);
            }
            case "recall": {
                String text = work.outputs.get("parse").path("recall_text").asText();
                JsonNode selected = work.outputs.get("router").path("selected_clusters");
                Map<String,ArrayNode> pool = algorithm.pool(selected);
                ArrayNode brief = algorithm.recallBrief(pool, work.disciplines);
                ArrayNode routing = store.json.createArrayNode();
                for (JsonNode item : selected) {
                    ObjectNode row = routing.addObject();
                    for (String key : Arrays.asList("cluster_id", "cluster_name", "route_level", "route_reason")) row.set(key, item.path(key));
                }
                ObjectNode request = store.json.createObjectNode().put("paper_text", text);
                request.set("selected_clusters", selected);
                request.set("candidate_pool", brief);
                input(stageId, request);
                JsonNode response = call(work, stageId, null, 1, definition, variables(
                    "paper_text", text, "selected_clusters_json", routing.toString(),
                    "candidate_pool_json", brief.toString(), "max_candidates", config.path("max_candidates").asText())).response;
                return algorithm.recall(response, brief, pool, work.disciplines, config);
            }
            case "judge":
                return judgeAll(work, stageId, definition);
            case "post": {
                ArrayNode judgments = store.json.createArrayNode();
                for (JsonNode item : work.outputs.get("judge").path("judgments")) {
                    ObjectNode row = ((ObjectNode)item).deepCopy();
                    row.remove("judgment_portrait");
                    judgments.add(row);
                }
                input(stageId, judgments);
                return algorithm.post(judgments, config, work.topK);
            }
            case "result": {
                ObjectNode output = ((ObjectNode)work.outputs.get("post")).deepCopy();
                input(stageId, output);
                JsonNode main = output.path("disciplines").path(0);
                output.set("main_discipline", main.isMissingNode() ? store.json.nullNode() : main);
                output.put("result_status", "succeeded").put("top_k", work.topK);
                return output;
            }
            default: throw new IllegalStateException("Unknown recognition stage");
        }
    }

    private ObjectNode judgeAll(Work work, long stageId, Map<String,Object> definition) {
        ArrayNode candidates = store.json.createArrayNode();
        work.outputs.get("recall").path("selected_candidates").forEach(candidates::add);
        Set<Long> seen = new HashSet<>();
        candidates.forEach(row -> seen.add(row.path("discipline_id").longValue()));
        for (JsonNode manual : work.overrides.path("manualDisciplineIds")) {
            ObjectNode discipline = work.byId.get(manual.longValue());
            if (discipline != null && seen.add(manual.longValue())) {
                candidates.add(algorithm.candidate(discipline, "manual", candidates.size() + 1, store.json.createArrayNode())
                    .put("recall_reason", "调试时人工加入"));
            }
        }
        ObjectNode input = store.json.createObjectNode();
        input.set("candidates", candidates);
        input.set("paper", work.outputs.get("parse").path("judgment_fields"));
        input(stageId, input);
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            long candidateId = writeCandidate(work, candidate);
            futures.add(CompletableFuture.supplyAsync(() -> {
                CurrentActor.set(work.actor);
                try { return judge(work, stageId, definition, candidate, candidateId); }
                finally { CurrentActor.clear(); }
            }, judges));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).get();
        } catch (InterruptedException e) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new StageFailure("interrupted", "执行被中断");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
            throw new IllegalStateException(e.getCause());
        }
        ObjectNode output = store.json.createObjectNode();
        ArrayNode judgments = output.putArray("judgments");
        int failed = 0;
        for (CompletableFuture<JsonNode> future : futures) {
            JsonNode judgment = future.join();
            judgments.add(judgment);
            if (judgment.path("judgment_failed").asBoolean()) failed++;
        }
        output.put("failed_count", failed);
        if (failed > 0) {
            store.db.update(STAGE, stageId, fields("output_json", output.toString()));
            throw new StageFailure("incomplete_candidate_judgments", failed + " 个候选学科精判失败，已保留逐次调用详情");
        }
        return output;
    }

    private JsonNode judge(Work work, long stageId, Map<String,Object> definition, JsonNode candidate, long candidateId) {
        ObjectNode discipline = work.byId.get(candidate.path("discipline_id").longValue());
        String code = discipline.path("category_id").asText();
        String judgeCode = code.matches("[0-9]+") ? code.replaceFirst("^0+(?!$)", "") : code;
        ObjectNode portrait = store.json.createObjectNode().put("category_id", judgeCode)
            .put("category_name", discipline.path("category_name").asText())
            .put("source", discipline.path("source").asText()).put("version", discipline.path("version").asText())
            .put("final_definition", discipline.path("judgment_text").asText());
        JsonNode paper = work.outputs.get("parse").path("judgment_fields");
        Map<String,String> variables = variables("title", paper.path("title").asText(), "doi", paper.path("doi").asText(),
            "journal_name", paper.path("journal_name").asText(), "pub_year", paper.path("pub_year").asText(),
            "abstract", paper.path("abstract").asText(), "candidate_portraits_json", store.json.createArrayNode().add(portrait).toString());
        JsonNode options = store.node(definition.get("callOptions"));
        JsonNode scores = store.node(work.configs.get("post").get("config")).path("level_scores");
        int limit = options.path("candidate_attempt_limit").asInt(1);
        int attempts = 0;
        Long outcome = null;
        StageFailure failure = null;
        for (int attempt = 1; attempt <= limit; attempt++) {
            attempts = attempt;
            try {
                Answer answer = call(work, stageId, candidateId, attempt, definition, variables);
                outcome = answer.callId;
                ObjectNode judged = (ObjectNode)algorithm.judgment(answer.response, candidate, portrait, scores);
                judged.put("llm_attempts", attempt);
                writeJudgment(candidateId, judged, outcome);
                return judged;
            } catch (RecognitionAlgorithm.InvalidAnswer e) {
                failure = new StageFailure("invalid_judgment", e.getMessage(), outcome);
            } catch (StageFailure e) {
                failure = e;
                outcome = e.callId;
            } catch (IdentityException e) {
                failure = new StageFailure("model_unavailable", e.getMessage());
                break;
            }
            if (attempt < limit) backoff(options.path("candidate_backoff_seconds_per_attempt").asDouble(), attempt);
        }
        ObjectNode judged = ((ObjectNode)candidate).deepCopy().put("judgment_failed", true).put("llm_attempts", attempts);
        judged.set("judgment_portrait", portrait);
        judged.put("error_code", failure == null ? "invalid_judgment" : failure.code)
            .put("error_message", failure == null ? "缺少精判结果" : failure.getMessage());
        writeJudgment(candidateId, judged, outcome);
        return judged;
    }

    private Answer call(Work work, long stageId, Long candidateId, int logicalNo, Map<String,Object> definition,
                        Map<String,String> variables) {
        JsonNode prompts = store.node(definition.get("prompts"));
        JsonNode options = store.node(definition.get("callOptions"));
        String rendered = algorithm.render(prompts.path("user").asText(), variables);
        long revision = id(definition, "modelRevisionId");
        int limit = options.path("llm_attempt_limit").asInt(1);
        StageFailure failure = new StageFailure("missing_response", "未执行模型调用");
        for (int attempt = 1; attempt <= limit; attempt++) {
            if (work.lease != null) work.lease.check();
            RecognitionChat call = models.prepareRecognitionChat(revision, requestSequence.getAndIncrement(),
                prompts.path("system").asText(), rendered, options);
            long callId = store.db.insert(CALL, fields("run_id", work.runId, "stage_id", stageId, "candidate_id", candidateId,
                "logical_call_no", logicalNo, "attempt_no", attempt, "model_connection_revision_id", revision,
                "endpoint_snapshot", call.endpoint, "request_json", call.requestEvidence()));
            long started = System.nanoTime();
            ChatReply reply = call.execute();
            if (work.lease != null) work.lease.check();
            JsonNode parsed = null;
            if (reply.errorCode == null) {
                parsed = parseReply(reply.content);
                if (parsed == null) {
                    reply.errorCode = "invalid_model_json";
                    reply.errorMessage = "模型文本中没有有效的 JSON 对象或数组";
                }
            }
            store.db.update(CALL, callId, fields("status", parsed == null ? "failed" : "succeeded", "raw_response", reply.raw,
                "parsed_response_json", parsed == null ? null : parsed.toString(), "usage_json", reply.usage,
                "provider_request_id", reply.requestId, "error_code", reply.errorCode, "error_message", reply.errorMessage,
                "finished_at", now(), "duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            if (parsed != null) return new Answer(callId, parsed);
            failure = new StageFailure(reply.errorCode, reply.errorMessage, callId);
            if (Arrays.asList("auth_failed", "not_found", "address_blocked").contains(reply.errorCode)) break;
            if (attempt < limit) backoff(options.path("llm_backoff_seconds_per_attempt").asDouble(), attempt);
        }
        throw failure;
    }

    private JsonNode parseReply(String content) {
        JsonNode direct = readObject(content);
        if (direct != null) return direct;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) return readObject(content.substring(start, end + 1));
        return null;
    }

    private JsonNode readObject(String content) {
        try {
            JsonNode value = store.json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(content);
            return value != null && (value.isObject() || value.isArray()) ? value : null;
        } catch (IOException e) { return null; }
    }

    private static void backoff(double seconds, int attempt) {
        try { Thread.sleep(Math.max(0, (long)(1000 * seconds * attempt))); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StageFailure("interrupted", "执行被中断");
        }
    }

    private void restoreJudgments(Work work, JsonNode prior) {
        for (JsonNode row : prior.path("judgments")) {
            long id = writeCandidate(work, row);
            writeJudgment(id, row, null);
        }
    }

    private long writeCandidate(Work work, JsonNode row) {
        long discipline = row.path("discipline_id").longValue();
        long id = store.db.insert("demo_run_candidate", fields("run_id", work.runId, "knowledge_version_id", work.knowledgeId,
            "knowledge_discipline_id", discipline, "recall_rank", row.path("recall_rank").intValue(),
            "recall_level", row.path("recall_level").asText(null), "recall_reason", row.path("recall_reason").asText(null),
            "recall_score", row.hasNonNull("effective_score") ? row.get("effective_score").doubleValue() : null,
            "score_source", row.path("score_source").asText(null), "candidate_source", row.path("candidate_source").asText(),
            "source_cluster_ids_json", row.path("source_clusters").toString(), "recall_detail_json", row.toString()));
        work.candidateIds.put(discipline, id);
        return id;
    }

    private void writeJudgment(long candidateId, JsonNode row, Long callId) {
        store.db.insert("demo_candidate_judgment", fields("candidate_id", candidateId, "outcome_call_id", callId,
            "status", row.path("judgment_failed").asBoolean() ? "failed" : "succeeded",
            "raw_level", row.path("raw_level").asText(null), "confidence", row.path("confidence").asText(null),
            "reason", row.path("reason").asText(null), "evidence_json", row.toString(),
            "error_code", row.path("error_code").asText(null), "error_message", row.path("error_message").asText(null)));
    }

    private void input(long stage, JsonNode value) {
        store.db.update(STAGE, stage, fields("input_json", value.toString()));
    }

    private void finishStage(long stage, JsonNode output, long duration) {
        store.db.update(STAGE, stage, fields("status", "succeeded", "output_json", output.toString(),
            "duration_ms", duration, "finished_at", now()));
    }

    private void publish(Work work, ObjectNode output, long stageId, long duration) {
        store.tx.execute(status -> {
            if (work.lease != null) work.lease.lock();
            JsonNode main = output.path("main_discipline");
            long result = store.db.insert("demo_run_result", fields("run_id", work.runId, "knowledge_version_id", work.knowledgeId,
                "revision_no", 1, "main_knowledge_discipline_id", main.isNull() ? null : main.path("discipline_id").longValue(),
                "summary", main.isNull() ? "没有达到保留条件的学科" : main.path("reason").asText(),
                "output_schema_version", work.configs.get("result").get("outputSchemaVersion"), "result_sha256", hash(output.toString())));
            for (JsonNode row : output.path("disciplines")) {
                long discipline = row.path("discipline_id").longValue();
                store.db.insert("demo_result_discipline", fields("run_id", work.runId, "result_id", result, "candidate_id",
                    work.candidateIds.get(discipline), "knowledge_discipline_id", discipline, "final_rank",
                    row.path("final_rank").intValue(), "final_level", row.path("final_level").asText()));
            }
            finishStage(stageId, output, duration);
            store.db.update(RUN, work.runId, fields("status", "succeeded", "current_result_id", result, "finished_at", now()));
            if (work.lease != null) work.lease.finish(result);
            return null;
        });
    }

    void fail(long runId, Map<String,Object> stage, StageFailure failure) {
        fail(runId, stage, failure, null);
    }

    private void fail(long runId, Map<String,Object> stage, StageFailure failure, Lease lease) {
        store.tx.execute(tx -> {
            if (lease != null) lease.lock();
            String code = stage == null ? null : (String)stage.get("stageCode");
            if (stage != null) {
                store.db.execute("UPDATE demo_run_stage SET status='failed',error_code=?,error_message=?,finished_at=?," +
                    "duration_ms=TIMESTAMPDIFF(MICROSECOND,started_at,CURRENT_TIMESTAMP(6)) DIV 1000 WHERE id=? AND status='running'",
                    failure.code, failure.getMessage(), now(), id(stage, "id"));
            }
            store.db.execute("UPDATE demo_run_stage SET status='skipped' WHERE run_id=? AND status='pending'", runId);
            store.db.execute("UPDATE demo_recognition_run SET status='failed',failed_stage_code=?,error_code=?,error_message=?," +
                "finished_at=? WHERE id=? AND status IN ('pending','running')", code, failure.code, failure.getMessage(), now(), runId);
            if (lease != null) lease.finish(null);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> definitions(Map<String,Object> version) {
        return (List<Map<String,Object>>)version.get("stages");
    }

    private final class Work {
        final long runId, actor, knowledgeId;
        final JsonNode input, overrides;
        final Lease lease;
        final int topK;
        final Map<String,Map<String,Object>> configs = new LinkedHashMap<>();
        final Map<String,JsonNode> outputs = new HashMap<>();
        final Map<String,ObjectNode> disciplines = new LinkedHashMap<>();
        final Map<Long,ObjectNode> byId = new HashMap<>();
        final Map<String,ObjectNode> clusters = new LinkedHashMap<>();
        final Map<Long,Long> candidateIds = new HashMap<>();

        Work(Map<String,Object> run, Map<String,Object> version, long actor, Lease lease) {
            this.actor = actor;
            this.lease = lease;
            runId = id(run, "id");
            knowledgeId = id(run, "knowledgeVersionId");
            input = store.node(run.get("rawInputJson"));
            overrides = store.node(run.get("executionOverridesJson"));
            for (Map<String,Object> stage : definitions(version)) configs.put((String)stage.get("stageCode"), stage);
            topK = store.node(configs.get("result").get("config")).path("top_k_default").asInt();
            loadKnowledge();
        }

        private void loadKnowledge() {
            for (Map<String,Object> row : store.db.query("SELECT p.id,p.code_snapshot,p.name_snapshot,p.source_tag_raw," +
                "p.source_portrait_version,p.portrait_json,p.judgment_text,s.id AS summary_id,s.summary_text,s.keywords_json " +
                "FROM demo_knowledge_discipline p LEFT JOIN demo_knowledge_summary s ON s.knowledge_discipline_id=p.id " +
                "AND s.knowledge_version_id=p.knowledge_version_id WHERE p.knowledge_version_id=? ORDER BY p.id", knowledgeId)) {
                long id = id(row, "id");
                JsonNode original = store.node(row.get("portraitJson"));
                ObjectNode discipline = store.json.createObjectNode().put("discipline_id", id)
                    .put("category_id", (String)row.get("codeSnapshot")).put("category_name", (String)row.get("nameSnapshot"))
                    .put("summary", (String)row.get("summaryText")).put("has_summary", id(row, "summaryId") != null)
                    .put("judgment_text", (String)row.get("judgmentText")).put("source", original.path("source").asText())
                    .put("version", original.path("version").asText());
                discipline.set("keywords", array(row.get("keywordsJson")));
                disciplines.put(discipline.path("category_id").asText(), discipline);
                byId.put(id, discipline);
            }
            for (Map<String,Object> row : store.db.query("SELECT id,cluster_code,name,summary_text,keywords_json " +
                "FROM demo_knowledge_cluster WHERE knowledge_version_id=? ORDER BY sort_order,id", knowledgeId)) {
                ObjectNode cluster = store.json.createObjectNode().put("cluster_id", (String)row.get("clusterCode"))
                    .put("cluster_name", (String)row.get("name")).put("cluster_summary", (String)row.get("summaryText"));
                cluster.set("keywords", array(row.get("keywordsJson")));
                cluster.putArray("member_codes");
                clusters.put(cluster.path("cluster_id").asText(), cluster);
            }
            for (Map<String,Object> row : store.db.query("SELECT c.cluster_code,d.code_snapshot FROM demo_knowledge_cluster_member m " +
                "JOIN demo_knowledge_cluster c ON c.id=m.cluster_id JOIN demo_knowledge_discipline d ON d.id=m.knowledge_discipline_id " +
                "WHERE m.knowledge_version_id=? ORDER BY m.cluster_id,m.sort_order,m.id", knowledgeId)) {
                ((ArrayNode)clusters.get(row.get("clusterCode")).get("member_codes")).add((String)row.get("codeSnapshot"));
            }
        }

        private JsonNode array(Object stored) {
            JsonNode data = store.node(stored);
            return data.isArray() ? data : store.json.createArrayNode();
        }
    }

    private static Map<String,String> variables(String... pairs) {
        Map<String,String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    static final class StageFailure extends RuntimeException {
        final String code;
        final Long callId;
        StageFailure(String code, String message) { this(code, message, null); }
        StageFailure(String code, String message, Long callId) { super(message); this.code = code; this.callId = callId; }
    }

    interface Lease {
        void check();
        void lock();
        void finish(Long result);
    }

    static final class LeaseLost extends RuntimeException { }

    private static final class Answer {
        final long callId;
        final JsonNode response;
        Answer(long callId, JsonNode response) { this.callId = callId; this.response = response; }
    }
}
