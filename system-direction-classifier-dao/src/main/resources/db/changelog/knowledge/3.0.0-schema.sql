--liquibase formatted sql

--changeset irlab:knowledge-3.0.0-file-asset dbms:mysql
CREATE TABLE demo_file_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(128),
    byte_size BIGINT UNSIGNED NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'staged',
    uploaded_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_asset_storage_key (storage_key),
    KEY idx_file_asset_sha256 (content_sha256),
    CONSTRAINT chk_file_asset_status CHECK (status IN ('staged', 'ready', 'failed', 'archived'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件资产及内容校验信息';

--changeset irlab:knowledge-3.0.0-asset-reference dbms:mysql
CREATE TABLE demo_asset_reference (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    object_type VARCHAR(40) NOT NULL,
    object_id BIGINT NOT NULL,
    role VARCHAR(40) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_reference (asset_id, object_type, object_id, role),
    KEY idx_asset_reference_object (object_type, object_id),
    CONSTRAINT fk_asset_reference_asset FOREIGN KEY (asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件被业务对象引用的记录';

--changeset irlab:knowledge-3.0.0-resource-grant dbms:mysql
CREATE TABLE demo_resource_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_type VARCHAR(40) NOT NULL,
    resource_id BIGINT NOT NULL,
    principal_type VARCHAR(16) NOT NULL,
    principal_id BIGINT NOT NULL,
    capability VARCHAR(32) NOT NULL,
    granted_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_grant (resource_type, resource_id, principal_type, principal_id, capability),
    KEY idx_resource_grant_principal (principal_type, principal_id, revoked_at),
    CONSTRAINT chk_resource_grant_principal CHECK (principal_type IN ('user', 'team'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务对象对用户或团队的授权';

--changeset irlab:knowledge-3.0.0-taxonomy dbms:mysql
CREATE TABLE demo_taxonomy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(255) NOT NULL,
    source_name VARCHAR(255),
    source_edition VARCHAR(100),
    description VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_taxonomy_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学科分类体系';

--changeset irlab:knowledge-3.0.0-discipline dbms:mysql
CREATE TABLE demo_discipline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    taxonomy_id BIGINT NOT NULL,
    canonical_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    display_name VARCHAR(255),
    status TINYINT DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_discipline_taxonomy_code (taxonomy_id, canonical_code),
    UNIQUE KEY uk_discipline_id_taxonomy (id, taxonomy_id),
    CONSTRAINT fk_discipline_taxonomy FOREIGN KEY (taxonomy_id) REFERENCES demo_taxonomy (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='体系内的稳定学科身份';

--changeset irlab:knowledge-3.0.0-base dbms:mysql
CREATE TABLE demo_knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT,
    taxonomy_id BIGINT NOT NULL,
    builtin_key VARCHAR(64) COLLATE utf8mb4_bin,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    owner_user_id BIGINT,
    visibility VARCHAR(16) NOT NULL DEFAULT 'private',
    team_id BIGINT,
    current_draft_id BIGINT,
    latest_version_id BIGINT,
    default_version_id BIGINT,
    copied_from_version_id BIGINT,
    archived_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_base_builtin (builtin_key),
    UNIQUE KEY uk_knowledge_base_id_taxonomy (id, taxonomy_id),
    KEY idx_knowledge_base_taxonomy (taxonomy_id, archived_at),
    KEY idx_knowledge_base_owner (owner_user_id, archived_at),
    KEY idx_knowledge_base_team (team_id, archived_at),
    CONSTRAINT fk_knowledge_base_taxonomy FOREIGN KEY (taxonomy_id) REFERENCES demo_taxonomy (id),
    CONSTRAINT chk_knowledge_base_visibility CHECK (visibility IN ('private', 'team', 'builtin')),
    CONSTRAINT chk_knowledge_base_team CHECK (visibility <> 'team' OR team_id IS NOT NULL),
    CONSTRAINT chk_knowledge_base_builtin CHECK ((builtin_key IS NULL) = (visibility <> 'builtin'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库主对象与版本指针';

--changeset irlab:knowledge-3.0.0-version dbms:mysql
CREATE TABLE demo_knowledge_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    taxonomy_id BIGINT NOT NULL,
    version_no INT UNSIGNED,
    state VARCHAR(16) NOT NULL DEFAULT 'draft',
    based_on_version_id BIGINT,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    change_note VARCHAR(1000),
    schema_version VARCHAR(64),
    manifest_json JSON,
    manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    validation_report_json JSON,
    saved_by BIGINT,
    saved_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    draft_slot BIGINT GENERATED ALWAYS AS (CASE WHEN state = 'draft' THEN knowledge_base_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_version_no (knowledge_base_id, version_no),
    UNIQUE KEY uk_knowledge_version_draft (draft_slot),
    UNIQUE KEY uk_knowledge_version_base_id (knowledge_base_id, id),
    UNIQUE KEY uk_knowledge_version_id_taxonomy (id, taxonomy_id),
    KEY idx_knowledge_version_manifest (knowledge_base_id, manifest_hash),
    CONSTRAINT fk_knowledge_version_base FOREIGN KEY (knowledge_base_id, taxonomy_id)
        REFERENCES demo_knowledge_base (id, taxonomy_id),
    CONSTRAINT fk_knowledge_version_parent FOREIGN KEY (knowledge_base_id, based_on_version_id)
        REFERENCES demo_knowledge_version (knowledge_base_id, id),
    CONSTRAINT chk_knowledge_version_state CHECK (
        (state = 'draft' AND version_no IS NULL AND saved_at IS NULL)
        OR (state = 'saved' AND version_no IS NOT NULL AND version_no > 0
            AND saved_at IS NOT NULL AND manifest_hash IS NOT NULL AND manifest_json IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可编辑草稿及已保存知识快照';

--changeset irlab:knowledge-3.0.0-version-pointers dbms:mysql
ALTER TABLE demo_knowledge_base
    ADD CONSTRAINT fk_knowledge_base_draft FOREIGN KEY (id, current_draft_id)
        REFERENCES demo_knowledge_version (knowledge_base_id, id),
    ADD CONSTRAINT fk_knowledge_base_latest FOREIGN KEY (id, latest_version_id)
        REFERENCES demo_knowledge_version (knowledge_base_id, id),
    ADD CONSTRAINT fk_knowledge_base_default FOREIGN KEY (id, default_version_id)
        REFERENCES demo_knowledge_version (knowledge_base_id, id),
    ADD CONSTRAINT fk_knowledge_base_copy_source FOREIGN KEY (copied_from_version_id, taxonomy_id)
        REFERENCES demo_knowledge_version (id, taxonomy_id);

--changeset irlab:knowledge-3.0.0-portrait dbms:mysql
CREATE TABLE demo_knowledge_discipline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    taxonomy_id BIGINT NOT NULL,
    discipline_id BIGINT NOT NULL,
    source_code VARCHAR(64) COLLATE utf8mb4_bin,
    code_snapshot VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    name_snapshot VARCHAR(255),
    source_label VARCHAR(255),
    source_tag_raw VARCHAR(128),
    source_portrait_version VARCHAR(255),
    portrait_schema_version VARCHAR(64),
    portrait_json JSON,
    original_definition LONGTEXT,
    final_definition LONGTEXT,
    expanded_portraits LONGTEXT,
    judgment_text LONGTEXT,
    judgment_projection_version VARCHAR(64),
    text_sync_status VARCHAR(32),
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    judgment_text_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    source_asset_id BIGINT,
    source_locator_json JSON,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_discipline_identity (knowledge_version_id, discipline_id),
    UNIQUE KEY uk_knowledge_discipline_code (knowledge_version_id, code_snapshot),
    UNIQUE KEY uk_knowledge_discipline_version_id (knowledge_version_id, id),
    CONSTRAINT fk_knowledge_discipline_version FOREIGN KEY (knowledge_version_id, taxonomy_id)
        REFERENCES demo_knowledge_version (id, taxonomy_id),
    CONSTRAINT fk_knowledge_discipline_identity FOREIGN KEY (discipline_id, taxonomy_id)
        REFERENCES demo_discipline (id, taxonomy_id),
    CONSTRAINT fk_knowledge_discipline_asset FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本内学科身份、完整画像及精判文本';

--changeset irlab:knowledge-3.0.0-summary dbms:mysql
CREATE TABLE demo_knowledge_summary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    knowledge_discipline_id BIGINT NOT NULL,
    summary_text LONGTEXT,
    keywords_json JSON,
    source_portrait_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    source_input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    provenance_status VARCHAR(32),
    provenance_json JSON,
    generation_item_id BIGINT,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    source_asset_id BIGINT,
    source_locator_json JSON,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_summary_discipline (knowledge_version_id, knowledge_discipline_id),
    CONSTRAINT fk_knowledge_summary_discipline FOREIGN KEY (knowledge_version_id, knowledge_discipline_id)
        REFERENCES demo_knowledge_discipline (knowledge_version_id, id),
    CONSTRAINT fk_knowledge_summary_asset FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用于学科召回的摘要及关键词';

--changeset irlab:knowledge-3.0.0-cluster dbms:mysql
CREATE TABLE demo_knowledge_cluster (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    cluster_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(255),
    summary_text LONGTEXT,
    keywords_json JSON,
    sort_order INT UNSIGNED,
    source_input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    provenance_json JSON,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    source_asset_id BIGINT,
    source_locator_json JSON,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_cluster_code (knowledge_version_id, cluster_code),
    UNIQUE KEY uk_knowledge_cluster_version_id (knowledge_version_id, id),
    KEY idx_knowledge_cluster_sort (knowledge_version_id, sort_order),
    CONSTRAINT fk_knowledge_cluster_version FOREIGN KEY (knowledge_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT fk_knowledge_cluster_asset FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本内学科簇及路由描述';

--changeset irlab:knowledge-3.0.0-cluster-member dbms:mysql
CREATE TABLE demo_knowledge_cluster_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    knowledge_discipline_id BIGINT NOT NULL,
    sort_order INT UNSIGNED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_cluster_member (cluster_id, knowledge_discipline_id),
    KEY idx_knowledge_member_discipline (knowledge_version_id, knowledge_discipline_id),
    KEY idx_knowledge_member_cluster (knowledge_version_id, cluster_id, sort_order),
    CONSTRAINT fk_knowledge_member_cluster FOREIGN KEY (knowledge_version_id, cluster_id)
        REFERENCES demo_knowledge_cluster (knowledge_version_id, id),
    CONSTRAINT fk_knowledge_member_discipline FOREIGN KEY (knowledge_version_id, knowledge_discipline_id)
        REFERENCES demo_knowledge_discipline (knowledge_version_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同一版本内的学科簇成员';

--changeset irlab:knowledge-3.0.0-seed-paper dbms:mysql
CREATE TABLE demo_knowledge_seed_paper (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    title TEXT,
    abstract LONGTEXT,
    keywords_json JSON,
    authors_json JSON,
    journal_name VARCHAR(512),
    publication_raw VARCHAR(255),
    publication_year SMALLINT UNSIGNED,
    doi_raw VARCHAR(512),
    doi_normalized VARCHAR(512),
    source_system VARCHAR(128),
    source_record_id VARCHAR(255),
    raw_record_json JSON,
    source_asset_id BIGINT,
    source_locator_json JSON,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_seed_version_id (knowledge_version_id, id),
    KEY idx_knowledge_seed_source (knowledge_version_id, source_system, source_record_id),
    CONSTRAINT fk_knowledge_seed_version FOREIGN KEY (knowledge_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT fk_knowledge_seed_asset FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本内种子论文快照';

--changeset irlab:knowledge-3.0.0-seed-discipline dbms:mysql
CREATE TABLE demo_seed_paper_discipline (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    seed_paper_id BIGINT NOT NULL,
    knowledge_discipline_id BIGINT NOT NULL,
    use_for_generation TINYINT(1) DEFAULT 1,
    assignment_source VARCHAR(128),
    remark VARCHAR(1000),
    PRIMARY KEY (id),
    UNIQUE KEY uk_seed_paper_discipline (seed_paper_id, knowledge_discipline_id),
    KEY idx_seed_paper_discipline_discipline (knowledge_version_id, knowledge_discipline_id),
    CONSTRAINT fk_seed_relation_paper FOREIGN KEY (knowledge_version_id, seed_paper_id)
        REFERENCES demo_knowledge_seed_paper (knowledge_version_id, id),
    CONSTRAINT fk_seed_relation_discipline FOREIGN KEY (knowledge_version_id, knowledge_discipline_id)
        REFERENCES demo_knowledge_discipline (knowledge_version_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='种子论文与学科的多对多关系';

--changeset irlab:knowledge-3.0.0-version-asset dbms:mysql
CREATE TABLE demo_knowledge_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    role VARCHAR(40) NOT NULL,
    logical_name VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    description VARCHAR(1000),
    applicable_scope_json JSON,
    metadata_json JSON,
    sort_order INT UNSIGNED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_asset_path (knowledge_version_id, logical_name),
    UNIQUE KEY uk_knowledge_asset_version_id (knowledge_version_id, id),
    KEY idx_knowledge_asset_role (knowledge_version_id, role, sort_order),
    CONSTRAINT fk_knowledge_asset_version FOREIGN KEY (knowledge_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT fk_knowledge_asset_file FOREIGN KEY (asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本资源清单及文件角色';

--changeset irlab:knowledge-3.0.0-import dbms:mysql
CREATE TABLE demo_knowledge_import (
    id BIGINT NOT NULL AUTO_INCREMENT,
    target_version_id BIGINT NOT NULL,
    target_revision BIGINT UNSIGNED NOT NULL,
    source_asset_id BIGINT,
    resource_type VARCHAR(40) NOT NULL,
    parser_version VARCHAR(64),
    mapping_json JSON,
    status VARCHAR(32) NOT NULL,
    counts_json JSON,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at DATETIME,
    PRIMARY KEY (id),
    KEY idx_knowledge_import_version (target_version_id, resource_type, status),
    CONSTRAINT fk_knowledge_import_version FOREIGN KEY (target_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT fk_knowledge_import_file FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入预览、映射和应用记录';

--changeset irlab:knowledge-3.0.0-import-item dbms:mysql
CREATE TABLE demo_knowledge_import_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_id BIGINT NOT NULL,
    source_asset_id BIGINT,
    source_locator_json JSON,
    source_key VARCHAR(255) COLLATE utf8mb4_bin,
    parsed_record_json JSON,
    validation_status VARCHAR(32) NOT NULL,
    issues_json JSON,
    action VARCHAR(32),
    applied_resource_id BIGINT,
    PRIMARY KEY (id),
    KEY idx_knowledge_import_item_status (import_id, validation_status, id),
    CONSTRAINT fk_knowledge_import_item_import FOREIGN KEY (import_id) REFERENCES demo_knowledge_import (id),
    CONSTRAINT fk_knowledge_import_item_file FOREIGN KEY (source_asset_id) REFERENCES demo_file_asset (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入行的来源、校验及采用结果';

--changeset irlab:knowledge-3.0.0-index dbms:mysql
CREATE TABLE demo_knowledge_index (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_version_id BIGINT NOT NULL,
    index_type VARCHAR(64) NOT NULL,
    source_manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    model_revision_id BIGINT,
    vector_space_signature VARCHAR(255),
    dimension INT UNSIGNED,
    entry_count BIGINT UNSIGNED,
    build_config_json JSON,
    knowledge_asset_id BIGINT,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_knowledge_index_type (knowledge_version_id, index_type, status),
    CONSTRAINT fk_knowledge_index_version FOREIGN KEY (knowledge_version_id) REFERENCES demo_knowledge_version (id),
    CONSTRAINT fk_knowledge_index_asset FOREIGN KEY (knowledge_version_id, knowledge_asset_id)
        REFERENCES demo_knowledge_asset (knowledge_version_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按策略启用的版本化索引及兼容信息';
