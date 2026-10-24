ALTER TABLE test_case_run_item
    ADD COLUMN arbitrated_label VARCHAR(16) NULL AFTER assignee_id,
    ADD COLUMN arbitrated_score DECIMAL(4,3) NULL AFTER arbitrated_label,
    ADD COLUMN arbitrated_by BIGINT NULL AFTER arbitrated_score,
    ADD COLUMN arbitrated_at DATETIME NULL AFTER arbitrated_by,
    ADD COLUMN arbitration_note VARCHAR(512) NULL AFTER arbitrated_at;
