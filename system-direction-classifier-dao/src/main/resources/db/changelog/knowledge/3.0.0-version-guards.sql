--liquibase formatted sql

--changeset irlab:knowledge-3.0.0-draft-guard dbms:mysql splitStatements:false
CREATE PROCEDURE demo_require_knowledge_draft(IN old_version_id BIGINT, IN new_version_id BIGINT)
BEGIN
    DECLARE version_state VARCHAR(16);
    IF old_version_id <> new_version_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cannot move a knowledge resource between versions';
    END IF;
    SELECT state INTO version_state FROM demo_knowledge_version WHERE id = old_version_id FOR SHARE;
    IF version_state IS NULL OR version_state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Knowledge resources can only be written in a draft';
    END IF;
END

--changeset irlab:knowledge-3.0.0-version-insert-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_knowledge_version_bi BEFORE INSERT ON demo_knowledge_version
FOR EACH ROW
BEGIN
    IF NEW.state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Create a draft before saving a knowledge version';
    END IF;
END

--changeset irlab:knowledge-3.0.0-version-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_knowledge_version_bu BEFORE UPDATE ON demo_knowledge_version
FOR EACH ROW
BEGIN
    IF OLD.state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Saved knowledge versions are immutable';
    END IF;
    IF NEW.id <> OLD.id OR NEW.knowledge_base_id <> OLD.knowledge_base_id OR NEW.taxonomy_id <> OLD.taxonomy_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cannot move a knowledge version between knowledge bases';
    END IF;
END

--changeset irlab:knowledge-3.0.0-version-delete-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_knowledge_version_bd BEFORE DELETE ON demo_knowledge_version
FOR EACH ROW
BEGIN
    IF OLD.state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Saved knowledge versions cannot be deleted';
    END IF;
END

--changeset irlab:knowledge-3.0.0-portrait-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_discipline_bi BEFORE INSERT ON demo_knowledge_discipline
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-portrait-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_discipline_bu BEFORE UPDATE ON demo_knowledge_discipline
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-portrait-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_discipline_bd BEFORE DELETE ON demo_knowledge_discipline
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-summary-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_summary_bi BEFORE INSERT ON demo_knowledge_summary
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-summary-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_summary_bu BEFORE UPDATE ON demo_knowledge_summary
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-summary-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_summary_bd BEFORE DELETE ON demo_knowledge_summary
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-cluster-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_cluster_bi BEFORE INSERT ON demo_knowledge_cluster
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-cluster-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_cluster_bu BEFORE UPDATE ON demo_knowledge_cluster
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-cluster-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_cluster_bd BEFORE DELETE ON demo_knowledge_cluster
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-cluster_member-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_cluster_member_bi BEFORE INSERT ON demo_knowledge_cluster_member
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-cluster_member-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_cluster_member_bu BEFORE UPDATE ON demo_knowledge_cluster_member
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-cluster_member-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_cluster_member_bd BEFORE DELETE ON demo_knowledge_cluster_member
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-seed_paper-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_seed_paper_bi BEFORE INSERT ON demo_knowledge_seed_paper
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-seed_paper-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_seed_paper_bu BEFORE UPDATE ON demo_knowledge_seed_paper
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-seed_paper-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_seed_paper_bd BEFORE DELETE ON demo_knowledge_seed_paper
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-seed_discipline-insert-guard dbms:mysql
CREATE TRIGGER demo_seed_paper_discipline_bi BEFORE INSERT ON demo_seed_paper_discipline
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-seed_discipline-update-guard dbms:mysql
CREATE TRIGGER demo_seed_paper_discipline_bu BEFORE UPDATE ON demo_seed_paper_discipline
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-seed_discipline-delete-guard dbms:mysql
CREATE TRIGGER demo_seed_paper_discipline_bd BEFORE DELETE ON demo_seed_paper_discipline
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-asset-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_asset_bi BEFORE INSERT ON demo_knowledge_asset
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-asset-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_asset_bu BEFORE UPDATE ON demo_knowledge_asset
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-asset-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_asset_bd BEFORE DELETE ON demo_knowledge_asset
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-index-insert-guard dbms:mysql
CREATE TRIGGER demo_knowledge_index_bi BEFORE INSERT ON demo_knowledge_index
FOR EACH ROW CALL demo_require_knowledge_draft(NEW.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-index-update-guard dbms:mysql
CREATE TRIGGER demo_knowledge_index_bu BEFORE UPDATE ON demo_knowledge_index
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, NEW.knowledge_version_id);

--changeset irlab:knowledge-3.0.0-index-delete-guard dbms:mysql
CREATE TRIGGER demo_knowledge_index_bd BEFORE DELETE ON demo_knowledge_index
FOR EACH ROW CALL demo_require_knowledge_draft(OLD.knowledge_version_id, OLD.knowledge_version_id);
