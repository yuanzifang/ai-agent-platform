ALTER TABLE test_case_run_item
    ADD COLUMN sampled TINYINT NOT NULL DEFAULT 0 AFTER label_count,
    ADD COLUMN assignee_id BIGINT NULL AFTER sampled,
    ADD KEY idx_calibration_assignee (assignee_id, run_id);
