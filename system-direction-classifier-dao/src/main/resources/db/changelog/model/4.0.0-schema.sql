--liquibase formatted sql

--changeset irlab:model-4.0.0-connection dbms:mysql
CREATE TABLE demo_model_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sample_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    owner_user_id BIGINT NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'private',
    team_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'enabled',
    current_revision_id BIGINT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_connection_sample (sample_code),
    KEY idx_model_connection_owner (owner_user_id, status),
    KEY idx_model_connection_team (team_id, status),
    CONSTRAINT fk_model_connection_owner FOREIGN KEY (owner_user_id) REFERENCES demo_user (id),
    CONSTRAINT fk_model_connection_team FOREIGN KEY (team_id) REFERENCES demo_team (id),
    CONSTRAINT chk_model_connection_visibility CHECK (visibility IN ('private', 'team', 'builtin')),
    CONSTRAINT chk_model_connection_team CHECK ((visibility = 'team') = (team_id IS NOT NULL)),
    CONSTRAINT chk_model_connection_status CHECK (status IN ('enabled', 'disabled', 'archived')),
    CONSTRAINT chk_model_connection_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型连接、所有权与当前配置';

--changeset irlab:model-4.0.0-revision dbms:mysql
CREATE TABLE demo_model_connection_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_id BIGINT NOT NULL,
    revision_no INT UNSIGNED NOT NULL,
    protocol_code VARCHAR(40) NOT NULL,
    provider_code VARCHAR(40),
    model_type VARCHAR(16) NOT NULL,
    base_url VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    auth_type VARCHAR(32) NOT NULL,
    credential_scope_key BINARY(32) NOT NULL,
    adapter_options_json JSON,
    routing_options_json JSON,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_revision_number (connection_id, revision_no),
    UNIQUE KEY uk_model_revision_identity (connection_id, id),
    UNIQUE KEY uk_model_revision_scope (connection_id, id, credential_scope_key),
    CONSTRAINT fk_model_revision_connection FOREIGN KEY (connection_id) REFERENCES demo_model_connection (id),
    CONSTRAINT chk_model_revision_number CHECK (revision_no > 0),
    CONSTRAINT chk_model_revision_type CHECK (model_type IN ('chat', 'embedding')),
    CONSTRAINT chk_model_revision_fields CHECK (CHAR_LENGTH(TRIM(protocol_code)) > 0 AND
        CHAR_LENGTH(TRIM(base_url)) > 0 AND CHAR_LENGTH(TRIM(model_id)) > 0 AND CHAR_LENGTH(TRIM(auth_type)) > 0),
    CONSTRAINT chk_model_revision_adapter CHECK (JSON_TYPE(adapter_options_json) = 'OBJECT'),
    CONSTRAINT chk_model_revision_routing CHECK (JSON_TYPE(routing_options_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不可变的模型调用配置修订';

--changeset irlab:model-4.0.0-current-revision dbms:mysql
ALTER TABLE demo_model_connection
    ADD CONSTRAINT fk_model_connection_current_revision FOREIGN KEY (id, current_revision_id)
        REFERENCES demo_model_connection_revision (connection_id, id);

--changeset irlab:model-4.0.0-credential dbms:mysql
CREATE TABLE demo_model_credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_id BIGINT NOT NULL,
    credential_scope_key BINARY(32) NOT NULL,
    credential_version INT UNSIGNED NOT NULL,
    encrypted_secret BLOB,
    secret_ref VARCHAR(512),
    encryption_key_id VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (IF(status = 'active', 1, NULL)) STORED,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_credential_version (connection_id, credential_scope_key, credential_version),
    UNIQUE KEY uk_model_credential_active (connection_id, credential_scope_key, active_slot),
    UNIQUE KEY uk_model_credential_scope (connection_id, credential_scope_key, id),
    CONSTRAINT fk_model_credential_connection FOREIGN KEY (connection_id) REFERENCES demo_model_connection (id),
    CONSTRAINT chk_model_credential_version CHECK (credential_version > 0),
    CONSTRAINT chk_model_credential_status CHECK ((status = 'active' AND revoked_at IS NULL) OR
        (status = 'revoked' AND revoked_at IS NOT NULL)),
    CONSTRAINT chk_model_credential_payload CHECK (
        (encrypted_secret IS NOT NULL AND OCTET_LENGTH(encrypted_secret) > 0 AND encryption_key_id IS NOT NULL AND secret_ref IS NULL)
        OR (encrypted_secret IS NULL AND encryption_key_id IS NULL AND secret_ref IS NOT NULL AND CHAR_LENGTH(TRIM(secret_ref)) > 0)
        OR (encrypted_secret IS NULL AND encryption_key_id IS NULL AND secret_ref IS NULL AND status = 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按服务范围轮换的加密模型凭据';

--changeset irlab:model-4.0.0-test dbms:mysql
CREATE TABLE demo_model_connection_test (
    id BIGINT NOT NULL AUTO_INCREMENT,
    connection_id BIGINT NOT NULL,
    connection_revision_id BIGINT NOT NULL,
    credential_scope_key BINARY(32) NOT NULL,
    credential_id BIGINT,
    tested_base_url VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tested_by BIGINT,
    tested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reachable BOOLEAN,
    call_status VARCHAR(16) NOT NULL,
    http_status SMALLINT UNSIGNED,
    duration_ms INT UNSIGNED NOT NULL,
    provider_request_id VARCHAR(160),
    returned_model_id VARCHAR(255),
    usage_json JSON,
    response_preview VARCHAR(500),
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    PRIMARY KEY (id),
    KEY idx_model_test_latest (connection_revision_id, credential_id, tested_at DESC, id DESC),
    CONSTRAINT fk_model_test_revision FOREIGN KEY (connection_id, connection_revision_id, credential_scope_key)
        REFERENCES demo_model_connection_revision (connection_id, id, credential_scope_key),
    CONSTRAINT fk_model_test_credential FOREIGN KEY (connection_id, credential_scope_key, credential_id)
        REFERENCES demo_model_credential (connection_id, credential_scope_key, id),
    CONSTRAINT chk_model_test_status CHECK ((call_status = 'success' AND reachable IS NOT NULL AND reachable = 1
        AND error_code IS NULL) OR (call_status = 'failed' AND error_code IS NOT NULL)),
    CONSTRAINT chk_model_test_reachable CHECK (reachable IN (0, 1)),
    CONSTRAINT chk_model_test_http CHECK (http_status IS NULL OR (http_status BETWEEN 100 AND 599 AND reachable IS NOT NULL AND reachable = 1)),
    CONSTRAINT chk_model_test_usage CHECK (JSON_TYPE(usage_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='逐次记录模型连接的真实测试结果';

--changeset irlab:model-4.0.0-revision-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_model_revision_bu BEFORE UPDATE ON demo_model_connection_revision
FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Model connection revisions are immutable';

--changeset irlab:model-4.0.0-revision-delete-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_model_revision_bd BEFORE DELETE ON demo_model_connection_revision
FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Model connection revisions cannot be deleted';

--changeset irlab:model-4.0.0-test-address-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_model_test_bi BEFORE INSERT ON demo_model_connection_test
FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM demo_model_connection_revision r
        WHERE r.connection_id = NEW.connection_id AND r.id = NEW.connection_revision_id
          AND (r.base_url = NEW.tested_base_url OR JSON_CONTAINS(r.routing_options_json,
              JSON_OBJECT('base_url', NEW.tested_base_url), '$.additional_endpoints') = 1)
          AND (r.auth_type <> 'none' OR NEW.credential_id IS NULL)
          AND (r.auth_type = 'none' OR NEW.call_status <> 'success' OR NEW.credential_id IS NOT NULL)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Model test address or authentication does not match the revision';
    END IF;
END;

--changeset irlab:model-4.0.0-test-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_model_test_bu BEFORE UPDATE ON demo_model_connection_test
FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Model connection tests are append-only';

--changeset irlab:model-4.0.0-test-delete-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_model_test_bd BEFORE DELETE ON demo_model_connection_test
FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Model connection tests are append-only';
