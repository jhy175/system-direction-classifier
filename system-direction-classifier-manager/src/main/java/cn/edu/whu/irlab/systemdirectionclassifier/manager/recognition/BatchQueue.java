package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.RecognitionStore.*;

/** MySQL is the queue. Each worker claim is fenced by an expiring token. */
@Component
@Profile("mysql")
final class BatchQueue {
    private static final Logger LOG = LoggerFactory.getLogger(BatchQueue.class);
    private static final int WORKERS = 2;
    private static final String LEASE_TIME = "CURRENT_TIMESTAMP(6) + INTERVAL 30 SECOND";
    private static final String AVAILABLE = "(status='pending' OR (status='running' AND lease_expires_at<=CURRENT_TIMESTAMP(6)))";
    private final RecognitionStore store;
    private final RecognitionExecutor executor;
    private final TransactionTemplate claims;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    private final Map<Long,Claim> active = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    BatchQueue(RecognitionStore store, RecognitionExecutor executor,
               org.springframework.transaction.PlatformTransactionManager transactions) {
        this.store = store;
        this.executor = executor;
        claims = new TransactionTemplate(transactions);
        claims.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @EventListener(ContextRefreshedEvent.class)
    void start() {
        if (started.compareAndSet(false, true)) ticker.scheduleWithFixedDelay(this::tick, 0, 1500, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
        workers.shutdownNow();
    }

    private void tick() {
        try {
            for (Claim claim : active.values()) {
                store.db.execute("UPDATE demo_batch_item SET lease_expires_at=" + LEASE_TIME +
                    " WHERE id=? AND claim_token=? AND lease_expires_at>CURRENT_TIMESTAMP(6)", claim.item, claim.token);
            }
            while (active.size() < WORKERS && !ticker.isShutdown()) {
                Claim claim = claims.execute(tx -> claim());
                if (claim == null) break;
                active.put(claim.item, claim);
                workers.execute(() -> {
                    try { executor.execute(claim.run, claim.actor, claim); }
                    finally { active.remove(claim.item, claim); }
                });
            }
        } catch (RuntimeException e) {
            if (!ticker.isShutdown()) LOG.warn("Batch worker queue tick failed ({})", e.getClass().getSimpleName());
        }
    }

    private Claim claim() {
        Map<String,Object> next = store.db.one("SELECT task_id FROM demo_batch_item WHERE " + AVAILABLE + " ORDER BY id LIMIT 1");
        if (next == null) return null;
        long task = id(next, "taskId");
        Map<String,Object> basis = store.db.one("SELECT t.*,v.manifest_hash FROM demo_batch_task t " +
            "JOIN demo_knowledge_version v ON v.id=t.knowledge_version_id " +
            "WHERE t.id=? AND t.status IN ('queued','running') FOR UPDATE OF t SKIP LOCKED", task);
        if (basis == null) return null;
        Map<String,Object> row = store.db.one("SELECT * FROM demo_batch_item WHERE task_id=? AND " + AVAILABLE +
            " ORDER BY id LIMIT 1 FOR UPDATE", task);
        if (row == null) return null;
        long item = id(row, "id");
        Long parent = id(row, "currentRunId");
        if ("running".equals(row.get("status"))) interrupt(parent);
        long method = id(basis, "methodVersionId");
        long actor = id(basis, "createdBy");
        long attempt = store.db.count("SELECT COALESCE(MAX(batch_attempt_no),0)+1 FROM demo_recognition_run WHERE batch_item_id=?", item);
        long run = store.db.insert(RUN, fields("input_snapshot_id", id(row, "inputSnapshotId"), "method_version_id", method,
            "knowledge_version_id", id(basis, "knowledgeVersionId"), "created_by", actor, "batch_item_id", item,
            "batch_attempt_no", attempt, "parent_run_id", parent, "trigger_type", parent == null ? "batch" : "batch_retry",
            "implementation_build", RecognitionAlgorithm.BUILD, "resource_manifest_sha256", basis.get("manifestHash"),
            "execution_overrides_json", basis.get("executionOverridesJson")));
        store.initializeStages(run, store.db.query("SELECT stage_code,output_schema_version FROM demo_method_stage WHERE method_version_id=? " +
            "ORDER BY FIELD(stage_code,'parse','router','recall','judge','post','result')", method), Collections.emptyMap());
        String token = UUID.randomUUID().toString();
        store.db.execute("UPDATE demo_batch_item SET status='running',current_run_id=?,claim_token=?,lease_expires_at=" +
            LEASE_TIME + " WHERE id=?", run, token, item);
        store.db.execute("UPDATE demo_batch_task SET status='running',started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),finished_at=NULL " +
            "WHERE id=?", task);
        return new Claim(task, item, run, actor, token);
    }

    private void interrupt(Long run) {
        if (run == null) return;
        Map<String,Object> phase = store.db.one("SELECT stage_code FROM demo_run_stage WHERE run_id=? AND status='running' ORDER BY id DESC LIMIT 1", run);
        store.db.execute("UPDATE demo_recognition_run SET status='failed',failed_stage_code=?,error_code='worker_interrupted'," +
            "error_message='后台领取已过期，条目将由新运行接续',finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('pending','running')",
            phase == null ? null : phase.get("stageCode"), run);
        store.db.execute("UPDATE demo_run_stage SET status='failed',error_code='worker_interrupted'," +
            "error_message='后台领取已过期',finished_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND status='running'", run);
        store.db.execute("UPDATE demo_run_stage SET status='skipped' WHERE run_id=? AND status='pending'", run);
        store.db.execute("UPDATE demo_model_call SET status='unknown',error_code='worker_interrupted'," +
            "error_message='中断时未收到确定响应',finished_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND status='running'", run);
    }

    private final class Claim implements RecognitionExecutor.Lease {
        final long task, item, run, actor;
        final String token;

        Claim(long task, long item, long run, long actor, String token) {
            this.task = task; this.item = item; this.run = run; this.actor = actor; this.token = token;
        }

        private void verify(boolean lock) {
            if (store.db.one("SELECT id FROM demo_batch_item WHERE id=? AND status='running' AND current_run_id=? " +
                "AND claim_token=? AND lease_expires_at>CURRENT_TIMESTAMP(6)" + (lock ? " FOR UPDATE" : ""),
                item, run, token) == null) throw new RecognitionExecutor.LeaseLost();
        }

        @Override public void check() { verify(false); }

        @Override public void lock() {
            store.db.find("demo_batch_task", task, true);
            verify(true);
        }

        @Override public void finish(Long result) {
            store.db.execute("UPDATE demo_batch_item SET status=?,adopted_run_id=?,adopted_result_id=?,claim_token=NULL,lease_expires_at=NULL " +
                "WHERE id=?", result == null ? "failed" : "succeeded", result == null ? null : run, result, item);
            Map<String,Object> counts = store.db.one("SELECT COUNT(*) AS total,COUNT(CASE WHEN status='failed' THEN 1 END) AS failures," +
                "COUNT(CASE WHEN status IN ('running','pending') THEN 1 END) AS remaining FROM demo_batch_item WHERE task_id=?", task);
            if (id(counts, "remaining") == 0) {
                long failures = id(counts, "failures");
                String status = failures == 0 ? "succeeded" : failures == id(counts, "total") ? "failed" : "partial_failed";
                store.db.execute("UPDATE demo_batch_task SET status=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?", status, task);
            }
        }
    }
}
