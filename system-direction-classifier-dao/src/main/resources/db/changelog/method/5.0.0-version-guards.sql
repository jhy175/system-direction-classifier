--liquibase formatted sql

--changeset irlab:method-5.0.0-draft-guard dbms:mysql splitStatements:false
CREATE PROCEDURE demo_require_method_draft(IN version_id BIGINT)
BEGIN
    DECLARE version_state VARCHAR(16);
    SELECT state INTO version_state FROM demo_method_version WHERE id = version_id FOR SHARE;
    IF version_state IS NULL OR version_state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Method configuration can only be written in a draft';
    END IF;
END

--changeset irlab:method-5.0.0-version-insert-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_method_version_bi BEFORE INSERT ON demo_method_version
FOR EACH ROW
BEGIN
    IF NEW.state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Create a draft before saving a method version';
    END IF;
END

--changeset irlab:method-5.0.0-version-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_method_version_bu BEFORE UPDATE ON demo_method_version
FOR EACH ROW
BEGIN
    IF OLD.state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Saved method versions are immutable';
    END IF;
    IF NEW.id <> OLD.id OR NEW.method_id <> OLD.method_id OR NEW.taxonomy_id <> OLD.taxonomy_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cannot move a method version between methods';
    END IF;
    IF NEW.state = 'saved' AND NOT EXISTS (
        SELECT 1 FROM demo_knowledge_version WHERE id = NEW.knowledge_version_id AND state = 'saved'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A saved method requires a saved knowledge version';
    END IF;
    IF NEW.state = 'saved' AND NOT EXISTS (
        SELECT 1 FROM demo_method_stage WHERE method_version_id = OLD.id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A saved method requires stage configuration';
    END IF;
END

--changeset irlab:method-5.0.0-version-delete-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_method_version_bd BEFORE DELETE ON demo_method_version
FOR EACH ROW
BEGIN
    IF OLD.state <> 'draft' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Saved method versions cannot be deleted';
    END IF;
END

--changeset irlab:method-5.0.0-pointer-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_method_bu BEFORE UPDATE ON demo_method
FOR EACH ROW
BEGIN
    IF NEW.current_draft_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM demo_method_version WHERE id = NEW.current_draft_id AND method_id = OLD.id AND state = 'draft'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'The current draft must be a draft of this method';
    END IF;
    IF (NEW.latest_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM demo_method_version WHERE id = NEW.latest_version_id AND method_id = OLD.id AND state = 'saved'
    )) OR (NEW.default_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM demo_method_version WHERE id = NEW.default_version_id AND method_id = OLD.id AND state = 'saved'
    )) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Default and latest versions must be saved versions of this method';
    END IF;
END

--changeset irlab:method-5.0.0-stage-insert-guard dbms:mysql
CREATE TRIGGER demo_method_stage_bi BEFORE INSERT ON demo_method_stage
FOR EACH ROW CALL demo_require_method_draft(NEW.method_version_id);

--changeset irlab:method-5.0.0-stage-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_method_stage_bu BEFORE UPDATE ON demo_method_stage
FOR EACH ROW
BEGIN
    IF NEW.id <> OLD.id OR NEW.method_version_id <> OLD.method_version_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cannot move a stage between method versions';
    END IF;
    CALL demo_require_method_draft(OLD.method_version_id);
END

--changeset irlab:method-5.0.0-stage-delete-guard dbms:mysql
CREATE TRIGGER demo_method_stage_bd BEFORE DELETE ON demo_method_stage
FOR EACH ROW CALL demo_require_method_draft(OLD.method_version_id);

--changeset irlab:method-5.0.0-stage-model-guard dbms:mysql splitStatements:false
CREATE PROCEDURE demo_require_method_stage_draft(IN stage_id BIGINT)
BEGIN
    DECLARE version_id BIGINT;
    SELECT method_version_id INTO version_id FROM demo_method_stage WHERE id = stage_id;
    CALL demo_require_method_draft(version_id);
END

--changeset irlab:method-5.0.0-stage-model-insert-guard dbms:mysql
CREATE TRIGGER demo_method_stage_model_bi BEFORE INSERT ON demo_method_stage_model
FOR EACH ROW CALL demo_require_method_stage_draft(NEW.method_stage_id);

--changeset irlab:method-5.0.0-stage-model-update-guard dbms:mysql splitStatements:false
CREATE TRIGGER demo_method_stage_model_bu BEFORE UPDATE ON demo_method_stage_model
FOR EACH ROW
BEGIN
    IF NEW.id <> OLD.id OR NEW.method_stage_id <> OLD.method_stage_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cannot move a model binding between method stages';
    END IF;
    CALL demo_require_method_stage_draft(OLD.method_stage_id);
END

--changeset irlab:method-5.0.0-stage-model-delete-guard dbms:mysql
CREATE TRIGGER demo_method_stage_model_bd BEFORE DELETE ON demo_method_stage_model
FOR EACH ROW CALL demo_require_method_stage_draft(OLD.method_stage_id);
