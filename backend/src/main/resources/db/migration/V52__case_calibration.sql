-- 用例回归人工标注/裁判校准队列：每条回归明细保留 LLM 裁判分，人工补分与标签
ALTER TABLE test_case_run_item
    ADD COLUMN human_score DECIMAL(4,3) NULL AFTER score,
    ADD COLUMN human_label VARCHAR(16) NULL AFTER human_score,
    ADD COLUMN reviewer_id BIGINT NULL AFTER human_label,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewer_id,
    ADD COLUMN review_note VARCHAR(512) NULL AFTER reviewed_at,
    ADD KEY idx_case_item_human (run_id, human_label);
