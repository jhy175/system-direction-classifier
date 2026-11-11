--liquibase formatted sql

--changeset irlab:method-5.0.0-method dbms:mysql
CREATE TABLE demo_method (
    id BIGINT NOT NULL AUTO_INCREMENT,
    taxonomy_id BIGINT NOT NULL,
    builtin_key VARCHAR(64) COLLATE utf8mb4_bin,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    owner_user_id BIGINT NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'private',
    team_id BIGINT,
    current_draft_id BIGINT,
    latest_version_id BIGINT,
    default_version_id BIGINT,
    copied_from_version_id BIGINT,
    archived_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_method_builtin (builtin_key),
    UNIQUE KEY uk_method_id_taxonomy (id, taxonomy_id),
    KEY idx_method_owner (owner_user_id, archived_at, updated_at),
    KEY idx_method_team (team_id, archived_at, updated_at),
    KEY idx_method_taxonomy (taxonomy_id, archived_at, updated_at),
    CONSTRAINT fk_method_taxonomy FOREIGN KEY (taxonomy_id) REFERENCES demo_taxonomy (id),
    CONSTRAINT fk_method_owner FOREIGN KEY (owner_user_id) REFERENCES demo_user (id),
    CONSTRAINT fk_method_team FOREIGN KEY (team_id) REFERENCES demo_team (id),
    CONSTRAINT chk_method_visibility CHECK (
        (visibility = 'private' AND team_id IS NULL AND builtin_key IS NULL)
        OR (visibility = 'team' AND team_id IS NOT NULL AND builtin_key IS NULL)
        OR (visibility = 'builtin' AND team_id IS NULL AND builtin_key IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='识别方法及版本指针';

--changeset irlab:method-5.0.0-version dbms:mysql
CREATE TABLE demo_method_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    method_id BIGINT NOT NULL,
    taxonomy_id BIGINT NOT NULL,
    version_no INT UNSIGNED,
    state VARCHAR(16) NOT NULL DEFAULT 'draft',
    based_on_version_id BIGINT,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    knowledge_version_id BIGINT,
    implementation_key VARCHAR(64) COLLATE utf8mb4_bin,
    implementation_revision VARCHAR(128) COLLATE utf8mb4_bin,
    config_schema_version VARCHAR(64) COLLATE utf8mb4_bin,
    config_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    change_note VARCHAR(1000),
    validation_report_json JSON,
    saved_by BIGINT,
    saved_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    draft_slot BIGINT GENERATED ALWAYS AS (CASE WHEN state = 'draft' THEN method_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_method_version_no (method_id, version_no),
    UNIQUE KEY uk_method_version_draft (draft_slot),
    UNIQUE KEY uk_method_version_method_id (method_id, id),
    UNIQUE KEY uk_method_version_id_taxonomy (id, taxonomy_id),
    KEY idx_method_version_hash (method_id, config_hash),
    KEY idx_method_version_knowledge (knowledge_version_id, taxonomy_id),
    CONSTRAINT fk_method_version_method FOREIGN KEY (method_id, taxonomy_id)
        REFERENCES demo_method (id, taxonomy_id),
    CONSTRAINT fk_method_version_source FOREIGN KEY (method_id, based_on_version_id)
        REFERENCES demo_method_version (method_id, id),
    CONSTRAINT fk_method_version_knowledge FOREIGN KEY (knowledge_version_id, taxonomy_id)
        REFERENCES demo_knowledge_version (id, taxonomy_id),
    CONSTRAINT fk_method_version_saver FOREIGN KEY (saved_by) REFERENCES demo_user (id),
    CONSTRAINT chk_method_version_state CHECK (
        (state = 'draft' AND version_no IS NULL AND saved_at IS NULL AND saved_by IS NULL)
        OR (state = 'saved' AND version_no IS NOT NULL AND version_no > 0
            AND saved_at IS NOT NULL AND saved_by IS NOT NULL AND knowledge_version_id IS NOT NULL
            AND implementation_key IS NOT NULL AND implementation_revision IS NOT NULL
            AND config_schema_version IS NOT NULL AND config_hash IS NOT NULL
            AND change_note IS NOT NULL AND CHAR_LENGTH(TRIM(change_note)) > 0
            AND validation_report_json IS NOT NULL)
    ),
    CONSTRAINT chk_method_version_report CHECK (JSON_TYPE(validation_report_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可编辑草稿和只读方法版本';

--changeset irlab:method-5.0.0-version-pointers dbms:mysql
ALTER TABLE demo_method
    ADD CONSTRAINT fk_method_current_draft FOREIGN KEY (id, current_draft_id)
        REFERENCES demo_method_version (method_id, id),
    ADD CONSTRAINT fk_method_latest_version FOREIGN KEY (id, latest_version_id)
        REFERENCES demo_method_version (method_id, id),
    ADD CONSTRAINT fk_method_default_version FOREIGN KEY (id, default_version_id)
        REFERENCES demo_method_version (method_id, id),
    ADD CONSTRAINT fk_method_copy_source FOREIGN KEY (copied_from_version_id, taxonomy_id)
        REFERENCES demo_method_version (id, taxonomy_id);

--changeset irlab:method-5.0.0-stage dbms:mysql
CREATE TABLE demo_method_stage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    method_version_id BIGINT NOT NULL,
    stage_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    strategy_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    config_json JSON,
    prompt_templates_json JSON,
    call_options_json JSON,
    output_schema_version VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    output_contract_json JSON,
    PRIMARY KEY (id),
    UNIQUE KEY uk_method_stage_code (method_version_id, stage_code),
    CONSTRAINT fk_method_stage_version FOREIGN KEY (method_version_id) REFERENCES demo_method_version (id),
    CONSTRAINT chk_method_stage_config CHECK (JSON_TYPE(config_json) = 'OBJECT'),
    CONSTRAINT chk_method_stage_templates CHECK (JSON_TYPE(prompt_templates_json) = 'OBJECT'),
    CONSTRAINT chk_method_stage_call CHECK (JSON_TYPE(call_options_json) = 'OBJECT'),
    CONSTRAINT chk_method_stage_output CHECK (JSON_TYPE(output_contract_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本内的阶段策略、提示词和生效参数';

--changeset irlab:method-5.0.0-stage-model dbms:mysql
CREATE TABLE demo_method_stage_model (
    id BIGINT NOT NULL AUTO_INCREMENT,
    method_stage_id BIGINT NOT NULL,
    model_role VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    model_connection_revision_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_method_stage_model_role (method_stage_id, model_role),
    KEY idx_method_stage_model_revision (model_connection_revision_id),
    CONSTRAINT fk_method_stage_model_stage FOREIGN KEY (method_stage_id) REFERENCES demo_method_stage (id),
    CONSTRAINT fk_method_stage_model_revision FOREIGN KEY (model_connection_revision_id)
        REFERENCES demo_model_connection_revision (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='方法阶段使用的固定模型配置修订';
