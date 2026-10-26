-- 步骤级评测结果人工复核：确定性/本地裁判之外的最终确认
ALTER TABLE eval_result
    ADD COLUMN review_status VARCHAR(16) NULL AFTER step_details,
    ADD COLUMN review_note VARCHAR(1000) NULL AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_note,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by;
