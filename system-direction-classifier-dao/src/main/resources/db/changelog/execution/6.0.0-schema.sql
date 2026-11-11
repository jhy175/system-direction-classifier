--liquibase formatted sql

--changeset irlab:execution-6.0.0-batch-task dbms:mysql
CREATE TABLE demo_batch_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_by BIGINT NOT NULL,
    method_version_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    selected_import_id BIGINT,
    submit_key VARCHAR(128) COLLATE utf8mb4_bin,
    execution_overrides_json JSON,
    status VARCHAR(24) NOT NULL DEFAULT 'draft',
    submitted_at DATETIME(6),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_task_submit_key (created_by, submit_key),
    UNIQUE KEY uk_batch_task_import_scope (id, selected_import_id),
    KEY idx_batch_task_owner_status (created_by, status, created_at),
    CONSTRAINT fk_batch_task_creator FOREIGN KEY (created_by) REFERENCES demo_user (id),
    CONSTRAINT fk_batch_task_method FOREIGN KEY (method_version_id) REFERENCES demo_method_version (id),
    CONSTRAINT fk_batch_task_knowledge FOREIGN KEY (knowledge_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT chk_batch_task_status CHECK (status IN ('draft', 'queued', 'running', 'succeeded', 'partial_failed', 'failed')),
    CONSTRAINT chk_batch_task_submission CHECK (
        (status = 'draft' AND submitted_at IS NULL)
        OR (status <> 'draft' AND submitted_at IS NOT NULL AND selected_import_id IS NOT NULL AND submit_key IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批量识别任务及提交依据';

--changeset irlab:execution-6.0.0-batch-import dbms:mysql
CREATE TABLE demo_batch_import (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    sheet_name VARCHAR(255),
    file_encoding VARCHAR(40),
    field_mapping_json JSON NOT NULL,
    validation_rules_json JSON NOT NULL,
    validation_summary_json JSON,
    issue_asset_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    error_message TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    checked_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_import_task_id (task_id, id),
    CONSTRAINT fk_batch_import_task FOREIGN KEY (task_id) REFERENCES demo_batch_task (id),
    CONSTRAINT fk_batch_import_file FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT fk_batch_import_issue_file FOREIGN KEY (issue_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT chk_batch_import_status CHECK (status IN ('pending', 'checking', 'checked', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一次批量文件导入及检查';

--changeset irlab:execution-6.0.0-batch-task-import-pointer dbms:mysql
ALTER TABLE demo_batch_task
    ADD CONSTRAINT fk_batch_task_selected_import FOREIGN KEY (id, selected_import_id)
        REFERENCES demo_batch_import (task_id, id);

--changeset irlab:execution-6.0.0-batch-row dbms:mysql
CREATE TABLE demo_batch_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_id BIGINT NOT NULL,
    source_row_no INT UNSIGNED NOT NULL,
    external_record_id VARCHAR(255),
    business_key VARCHAR(512) COLLATE utf8mb4_bin,
    raw_row_json JSON NOT NULL,
    mapped_input_json JSON,
    issues_json JSON,
    validation_status VARCHAR(16) NOT NULL,
    skipped_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_row_position (import_id, source_row_no),
    UNIQUE KEY uk_batch_row_import_id (import_id, id),
    KEY idx_batch_row_business_key (import_id, business_key),
    CONSTRAINT fk_batch_row_import FOREIGN KEY (import_id) REFERENCES demo_batch_import (id),
    CONSTRAINT chk_batch_row_number CHECK (source_row_no > 0),
    CONSTRAINT chk_batch_row_validation CHECK (validation_status IN ('valid', 'invalid'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批量输入原始行及校验结果';

--changeset irlab:execution-6.0.0-input-snapshot dbms:mysql
CREATE TABLE demo_input_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_by BIGINT NOT NULL,
    source_batch_row_id BIGINT,
    content_type VARCHAR(48),
    title TEXT,
    keywords TEXT,
    abstract_text MEDIUMTEXT,
    journal_name VARCHAR(512),
    doi VARCHAR(512),
    publication_raw VARCHAR(255),
    publication_year SMALLINT UNSIGNED,
    external_record_id VARCHAR(255),
    raw_input_json JSON NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_input_snapshot_batch_row (source_batch_row_id, id),
    CONSTRAINT fk_input_snapshot_creator FOREIGN KEY (created_by) REFERENCES demo_user (id),
    CONSTRAINT fk_input_snapshot_batch_row FOREIGN KEY (source_batch_row_id) REFERENCES demo_batch_row (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单篇与批量共用的论文输入快照';

--changeset irlab:execution-6.0.0-batch-item dbms:mysql
CREATE TABLE demo_batch_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    import_id BIGINT NOT NULL,
    batch_row_id BIGINT NOT NULL,
    input_snapshot_id BIGINT NOT NULL,
    current_run_id BIGINT,
    adopted_run_id BIGINT,
    adopted_result_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    claim_token VARCHAR(64) COLLATE utf8mb4_bin,
    lease_expires_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_item_source (task_id, batch_row_id),
    UNIQUE KEY uk_batch_item_input (id, input_snapshot_id),
    KEY idx_batch_item_task_status (task_id, status, id),
    KEY idx_batch_item_queue (status, lease_expires_at, id),
    CONSTRAINT fk_batch_item_task_import FOREIGN KEY (task_id, import_id)
        REFERENCES demo_batch_task (id, selected_import_id),
    CONSTRAINT fk_batch_item_import_row FOREIGN KEY (import_id, batch_row_id) REFERENCES demo_batch_row (import_id, id),
    CONSTRAINT fk_batch_item_input_row FOREIGN KEY (batch_row_id, input_snapshot_id)
        REFERENCES demo_input_snapshot (source_batch_row_id, id),
    CONSTRAINT chk_batch_item_status CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
    CONSTRAINT chk_batch_item_lease CHECK (
        (status = 'running' AND current_run_id IS NOT NULL AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'running' AND claim_token IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT chk_batch_item_adopted CHECK (
        (status = 'succeeded' AND adopted_run_id IS NOT NULL AND adopted_result_id IS NOT NULL)
        OR (status <> 'succeeded' AND adopted_run_id IS NULL AND adopted_result_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批量任务中一条可执行论文及采用结果';

--changeset irlab:execution-6.0.0-recognition-run dbms:mysql
CREATE TABLE demo_recognition_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    input_snapshot_id BIGINT NOT NULL,
    method_version_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    batch_item_id BIGINT,
    batch_attempt_no INT UNSIGNED,
    parent_run_id BIGINT,
    trigger_type VARCHAR(24) NOT NULL,
    start_stage_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'parse',
    failed_stage_code VARCHAR(32) COLLATE utf8mb4_bin,
    implementation_build VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    resource_manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_bundle_asset_id BIGINT,
    execution_overrides_json JSON,
    current_result_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    error_code VARCHAR(64),
    error_message TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_recognition_run_item_attempt (batch_item_id, batch_attempt_no),
    UNIQUE KEY uk_recognition_run_item_id (batch_item_id, id),
    UNIQUE KEY uk_recognition_run_parent_id (parent_run_id, id),
    UNIQUE KEY uk_recognition_run_knowledge_id (id, knowledge_version_id),
    KEY idx_recognition_run_owner_history (created_by, created_at),
    CONSTRAINT fk_recognition_run_input FOREIGN KEY (input_snapshot_id) REFERENCES demo_input_snapshot (id),
    CONSTRAINT fk_recognition_run_method FOREIGN KEY (method_version_id) REFERENCES demo_method_version (id),
    CONSTRAINT fk_recognition_run_knowledge FOREIGN KEY (knowledge_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT fk_recognition_run_creator FOREIGN KEY (created_by) REFERENCES demo_user (id),
    CONSTRAINT fk_recognition_run_item FOREIGN KEY (batch_item_id, input_snapshot_id)
        REFERENCES demo_batch_item (id, input_snapshot_id),
    CONSTRAINT fk_recognition_run_parent FOREIGN KEY (parent_run_id) REFERENCES demo_recognition_run (id),
    CONSTRAINT fk_recognition_run_start_stage FOREIGN KEY (method_version_id, start_stage_code)
        REFERENCES demo_method_stage (method_version_id, stage_code),
    CONSTRAINT fk_recognition_run_failed_stage FOREIGN KEY (method_version_id, failed_stage_code)
        REFERENCES demo_method_stage (method_version_id, stage_code),
    CONSTRAINT fk_recognition_run_bundle FOREIGN KEY (resource_bundle_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT chk_recognition_run_batch_attempt CHECK (
        (batch_item_id IS NULL AND batch_attempt_no IS NULL)
        OR (batch_item_id IS NOT NULL AND batch_attempt_no IS NOT NULL AND batch_attempt_no > 0)
    ),
    CONSTRAINT chk_recognition_run_trigger CHECK (trigger_type IN (
        'single', 'batch', 'batch_retry', 'copy_input', 'rerun', 'stage_retry', 'resume', 'intervention'
    )),
    CONSTRAINT chk_recognition_run_status CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
    CONSTRAINT chk_recognition_run_published CHECK ((status = 'succeeded') = (current_result_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一次单篇识别执行及其固定依据';

--changeset irlab:execution-6.0.0-run-stage dbms:mysql
CREATE TABLE demo_run_stage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    stage_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    source_stage_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    payload_schema_version VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    input_json JSON,
    output_json JSON,
    input_asset_id BIGINT,
    output_asset_id BIGINT,
    error_code VARCHAR(64),
    error_message TEXT,
    duration_ms BIGINT UNSIGNED,
    started_at DATETIME(6),
    finished_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_stage_code (run_id, stage_code),
    UNIQUE KEY uk_run_stage_run_id (run_id, id),
    UNIQUE KEY uk_run_stage_source_scope (stage_code, id),
    CONSTRAINT fk_run_stage_run FOREIGN KEY (run_id) REFERENCES demo_recognition_run (id),
    CONSTRAINT fk_run_stage_source FOREIGN KEY (stage_code, source_stage_id) REFERENCES demo_run_stage (stage_code, id),
    CONSTRAINT fk_run_stage_input_asset FOREIGN KEY (input_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT fk_run_stage_output_asset FOREIGN KEY (output_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT chk_run_stage_status CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'reused', 'needs_rerun', 'skipped')),
    CONSTRAINT chk_run_stage_reuse CHECK ((status = 'reused') = (source_stage_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='识别阶段输入输出和复用来源';

--changeset irlab:execution-6.0.0-run-candidate dbms:mysql
CREATE TABLE demo_run_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    knowledge_discipline_id BIGINT NOT NULL,
    recall_rank INT UNSIGNED,
    recall_level VARCHAR(40),
    recall_reason TEXT,
    recall_score DOUBLE,
    score_source VARCHAR(32),
    candidate_source VARCHAR(24) NOT NULL,
    source_cluster_ids_json JSON,
    recall_detail_json JSON,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_candidate_discipline (run_id, knowledge_discipline_id),
    UNIQUE KEY uk_run_candidate_rank (run_id, recall_rank),
    UNIQUE KEY uk_run_candidate_run_id (run_id, id),
    UNIQUE KEY uk_run_candidate_result_identity (run_id, id, knowledge_discipline_id),
    CONSTRAINT fk_run_candidate_run FOREIGN KEY (run_id, knowledge_version_id)
        REFERENCES demo_recognition_run (id, knowledge_version_id),
    CONSTRAINT fk_run_candidate_discipline FOREIGN KEY (knowledge_version_id, knowledge_discipline_id)
        REFERENCES demo_knowledge_discipline (knowledge_version_id, id),
    CONSTRAINT chk_run_candidate_rank CHECK (recall_rank > 0),
    CONSTRAINT chk_run_candidate_source CHECK (candidate_source IN ('model', 'cluster_fill', 'global_fallback', 'manual')),
    CONSTRAINT chk_run_candidate_score CHECK ((recall_score IS NULL) = (score_source IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实际进入独立精判的学科候选';

--changeset irlab:execution-6.0.0-model-call dbms:mysql
CREATE TABLE demo_model_call (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    stage_id BIGINT NOT NULL,
    candidate_id BIGINT,
    candidate_slot BIGINT GENERATED ALWAYS AS (COALESCE(candidate_id, 0)) STORED,
    logical_call_no INT UNSIGNED NOT NULL,
    attempt_no INT UNSIGNED NOT NULL,
    model_connection_revision_id BIGINT NOT NULL,
    endpoint_snapshot VARCHAR(1024),
    request_json JSON,
    request_asset_id BIGINT,
    raw_response MEDIUMTEXT,
    response_asset_id BIGINT,
    parsed_response_json JSON,
    provider_request_id VARCHAR(255),
    usage_json JSON,
    status VARCHAR(16) NOT NULL DEFAULT 'running',
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    error_message TEXT,
    duration_ms BIGINT UNSIGNED,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_call_attempt (stage_id, candidate_slot, logical_call_no, attempt_no),
    UNIQUE KEY uk_model_call_candidate_id (candidate_id, id),
    CONSTRAINT fk_model_call_stage FOREIGN KEY (run_id, stage_id) REFERENCES demo_run_stage (run_id, id),
    CONSTRAINT fk_model_call_candidate FOREIGN KEY (run_id, candidate_id) REFERENCES demo_run_candidate (run_id, id),
    CONSTRAINT fk_model_call_revision FOREIGN KEY (model_connection_revision_id) REFERENCES demo_model_connection_revision (id),
    CONSTRAINT fk_model_call_request_asset FOREIGN KEY (request_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT fk_model_call_response_asset FOREIGN KEY (response_asset_id) REFERENCES demo_file_asset (id),
    CONSTRAINT chk_model_call_numbers CHECK (logical_call_no > 0 AND attempt_no > 0),
    CONSTRAINT chk_model_call_status CHECK (status IN ('running', 'succeeded', 'failed', 'unknown'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型逻辑调用的逐次尝试及缓存命中';

--changeset irlab:execution-6.0.0-candidate-judgment dbms:mysql
CREATE TABLE demo_candidate_judgment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    candidate_id BIGINT NOT NULL,
    outcome_call_id BIGINT,
    status VARCHAR(16) NOT NULL,
    raw_level VARCHAR(40),
    confidence VARCHAR(128),
    reason TEXT,
    evidence_json JSON,
    error_code VARCHAR(64),
    error_message TEXT,
    finished_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_candidate_judgment_candidate (candidate_id),
    CONSTRAINT fk_candidate_judgment_candidate FOREIGN KEY (candidate_id) REFERENCES demo_run_candidate (id),
    CONSTRAINT fk_candidate_judgment_call FOREIGN KEY (candidate_id, outcome_call_id) REFERENCES demo_model_call (candidate_id, id),
    CONSTRAINT chk_candidate_judgment_outcome CHECK (
        (status = 'succeeded' AND raw_level IS NOT NULL) OR (status = 'failed' AND raw_level IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每个候选的原始精判结论';

--changeset irlab:execution-6.0.0-run-result dbms:mysql
CREATE TABLE demo_run_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    revision_no INT UNSIGNED NOT NULL,
    based_on_result_id BIGINT,
    main_knowledge_discipline_id BIGINT,
    summary TEXT,
    output_schema_version VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_result_revision (run_id, revision_no),
    UNIQUE KEY uk_run_result_run_id (run_id, id),
    KEY idx_run_result_main (main_knowledge_discipline_id, run_id),
    CONSTRAINT fk_run_result_run FOREIGN KEY (run_id, knowledge_version_id)
        REFERENCES demo_recognition_run (id, knowledge_version_id),
    CONSTRAINT fk_run_result_base FOREIGN KEY (run_id, based_on_result_id) REFERENCES demo_run_result (run_id, id),
    CONSTRAINT fk_run_result_main FOREIGN KEY (knowledge_version_id, main_knowledge_discipline_id)
        REFERENCES demo_knowledge_discipline (knowledge_version_id, id),
    CONSTRAINT chk_run_result_revision CHECK (revision_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='识别结果修订，允许成功的空学科结果';

--changeset irlab:execution-6.0.0-result-discipline dbms:mysql
CREATE TABLE demo_result_discipline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    result_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    knowledge_discipline_id BIGINT NOT NULL,
    final_rank INT UNSIGNED NOT NULL,
    final_level VARCHAR(40) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_discipline_rank (result_id, final_rank),
    UNIQUE KEY uk_result_discipline_identity (result_id, knowledge_discipline_id),
    KEY idx_result_discipline_filter (knowledge_discipline_id, result_id),
    CONSTRAINT fk_result_discipline_result FOREIGN KEY (run_id, result_id) REFERENCES demo_run_result (run_id, id),
    CONSTRAINT fk_result_discipline_candidate FOREIGN KEY (run_id, candidate_id, knowledge_discipline_id)
        REFERENCES demo_run_candidate (run_id, id, knowledge_discipline_id),
    CONSTRAINT chk_result_discipline_rank CHECK (final_rank > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='某次结果修订最终入选的学科';

--changeset irlab:execution-6.0.0-result-metric dbms:mysql
CREATE TABLE demo_result_metric (
    id BIGINT NOT NULL AUTO_INCREMENT,
    result_id BIGINT NOT NULL,
    metric_code VARCHAR(48) COLLATE utf8mb4_bin NOT NULL,
    metric_value DECIMAL(38,18),
    status VARCHAR(16) NOT NULL,
    source_name VARCHAR(255),
    source_method VARCHAR(128),
    source_version VARCHAR(128),
    generated_at DATETIME(6),
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    error_message TEXT,
    detail_json JSON,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_metric_code (result_id, metric_code),
    CONSTRAINT fk_result_metric_result FOREIGN KEY (result_id) REFERENCES demo_run_result (id),
    CONSTRAINT chk_result_metric_status CHECK (status IN ('not_provided', 'valid', 'needs_update', 'failed')),
    CONSTRAINT chk_result_metric_value CHECK ((status = 'valid') = (metric_value IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绑定具体结果修订的外部指标';

--changeset irlab:execution-6.0.0-run-result-pointer dbms:mysql
ALTER TABLE demo_recognition_run
    ADD CONSTRAINT fk_recognition_run_current_result FOREIGN KEY (id, current_result_id)
        REFERENCES demo_run_result (run_id, id);

--changeset irlab:execution-6.0.0-batch-item-pointers dbms:mysql
ALTER TABLE demo_batch_item
    ADD CONSTRAINT fk_batch_item_current_run FOREIGN KEY (id, current_run_id) REFERENCES demo_recognition_run (batch_item_id, id),
    ADD CONSTRAINT fk_batch_item_adopted_run FOREIGN KEY (id, adopted_run_id) REFERENCES demo_recognition_run (batch_item_id, id),
    ADD CONSTRAINT fk_batch_item_adopted_result FOREIGN KEY (adopted_run_id, adopted_result_id) REFERENCES demo_run_result (run_id, id);

--changeset irlab:execution-6.0.0-run-intervention dbms:mysql
CREATE TABLE demo_run_intervention (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_run_id BIGINT NOT NULL,
    child_run_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    intervention_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    target_knowledge_discipline_id BIGINT,
    affected_stage_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    change_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_run_intervention_child (child_run_id, id),
    CONSTRAINT fk_run_intervention_branch FOREIGN KEY (source_run_id, child_run_id)
        REFERENCES demo_recognition_run (parent_run_id, id),
    CONSTRAINT fk_run_intervention_actor FOREIGN KEY (actor_id) REFERENCES demo_user (id),
    CONSTRAINT fk_run_intervention_discipline FOREIGN KEY (target_knowledge_discipline_id) REFERENCES demo_knowledge_discipline (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工干预和对应运行分支';

--changeset irlab:execution-6.0.0-debug-record dbms:mysql
CREATE TABLE demo_debug_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    result_id BIGINT,
    name VARCHAR(255),
    saved_by BIGINT NOT NULL,
    saved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_debug_record_owner_time (saved_by, saved_at, id),
    CONSTRAINT fk_debug_record_run FOREIGN KEY (run_id) REFERENCES demo_recognition_run (id),
    CONSTRAINT fk_debug_record_result FOREIGN KEY (run_id, result_id) REFERENCES demo_run_result (run_id, id),
    CONSTRAINT fk_debug_record_saver FOREIGN KEY (saved_by) REFERENCES demo_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户保存的单篇调试历史';

--changeset irlab:execution-6.0.0-task-insert-guard dbms:mysql splitStatements:false
CREATE TRIGGER trg_batch_task_basis_insert BEFORE INSERT ON demo_batch_task FOR EACH ROW
BEGIN
    IF NEW.status <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Batch task must start as a draft';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM demo_method_version
        WHERE id = NEW.method_version_id AND knowledge_version_id = NEW.knowledge_version_id AND state = 'saved') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Batch task requires a saved method and its knowledge version';
    END IF;
END;

--changeset irlab:execution-6.0.0-task-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER trg_batch_task_basis_update BEFORE UPDATE ON demo_batch_task FOR EACH ROW
BEGIN
    IF OLD.status <> 'draft' THEN
        IF NEW.status = 'draft' OR NEW.created_by <> OLD.created_by OR NEW.method_version_id <> OLD.method_version_id
            OR NEW.knowledge_version_id <> OLD.knowledge_version_id OR NOT (NEW.selected_import_id <=> OLD.selected_import_id)
            OR NOT (NEW.submit_key <=> OLD.submit_key) OR NOT (NEW.submitted_at <=> OLD.submitted_at)
            OR NOT (NEW.execution_overrides_json <=> OLD.execution_overrides_json) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Submitted batch execution basis is fixed';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM demo_method_version
            WHERE id = NEW.method_version_id AND knowledge_version_id = NEW.knowledge_version_id AND state = 'saved') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Batch task requires a saved method and its knowledge version';
        END IF;
        IF NEW.status <> 'draft' AND NOT EXISTS (SELECT 1 FROM demo_batch_import
            WHERE id = NEW.selected_import_id AND task_id = OLD.id AND status = 'checked') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Batch submission requires a checked import';
        END IF;
    END IF;
END;

--changeset irlab:execution-6.0.0-item-insert-guard dbms:mysql splitStatements:false
CREATE TRIGGER trg_batch_item_valid_row BEFORE INSERT ON demo_batch_item FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM demo_batch_row
        WHERE id = NEW.batch_row_id AND import_id = NEW.import_id AND validation_status = 'valid' AND skipped_at IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Batch item requires a valid row selected for execution';
    END IF;
END;

--changeset irlab:execution-6.0.0-run-insert-guard dbms:mysql splitStatements:false
CREATE TRIGGER trg_recognition_run_basis_insert BEFORE INSERT ON demo_recognition_run FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM demo_method_version
        WHERE id = NEW.method_version_id AND knowledge_version_id = NEW.knowledge_version_id AND state = 'saved') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recognition run requires a saved method and its knowledge version';
    END IF;
    IF NEW.batch_item_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM demo_batch_item i JOIN demo_batch_task t ON t.id = i.task_id
        WHERE i.id = NEW.batch_item_id AND t.status <> 'draft' AND t.method_version_id = NEW.method_version_id
            AND t.knowledge_version_id = NEW.knowledge_version_id
            AND t.execution_overrides_json <=> NEW.execution_overrides_json
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Batch run must use the submitted task execution basis';
    END IF;
    IF NEW.trigger_type IN ('batch_retry', 'copy_input', 'rerun', 'stage_retry', 'resume', 'intervention')
        AND NEW.parent_run_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Derived run requires a source run';
    END IF;
END;

--changeset irlab:execution-6.0.0-run-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER trg_recognition_run_basis_update BEFORE UPDATE ON demo_recognition_run FOR EACH ROW
BEGIN
    IF NEW.input_snapshot_id <> OLD.input_snapshot_id OR NEW.method_version_id <> OLD.method_version_id
        OR NEW.knowledge_version_id <> OLD.knowledge_version_id OR NEW.created_by <> OLD.created_by
        OR NEW.implementation_build <> OLD.implementation_build OR NEW.resource_manifest_sha256 <> OLD.resource_manifest_sha256
        OR NEW.trigger_type <> OLD.trigger_type OR NEW.start_stage_code <> OLD.start_stage_code
        OR NOT (NEW.batch_item_id <=> OLD.batch_item_id) OR NOT (NEW.batch_attempt_no <=> OLD.batch_attempt_no)
        OR NOT (NEW.parent_run_id <=> OLD.parent_run_id) OR NOT (NEW.resource_bundle_asset_id <=> OLD.resource_bundle_asset_id)
        OR NOT (NEW.execution_overrides_json <=> OLD.execution_overrides_json) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Recognition run execution basis is fixed';
    END IF;
    IF OLD.status IN ('succeeded', 'failed') AND NEW.status <> OLD.status THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retry requires a new recognition run';
    END IF;
END;
