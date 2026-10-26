-- 黄金测试集步骤级评测：ANSWER / REWRITE / RETRIEVAL / RERANK
ALTER TABLE eval_dataset_item
    ADD COLUMN step_type VARCHAR(16) NOT NULL DEFAULT 'ANSWER' AFTER score_criteria,
    ADD COLUMN expected_payload JSON NULL AFTER step_type,
    ADD COLUMN step_config JSON NULL AFTER expected_payload;

ALTER TABLE eval_result
    ADD COLUMN step_type VARCHAR(16) NULL AFTER metric_key,
    ADD COLUMN step_passed TINYINT NULL AFTER step_type,
    ADD COLUMN step_details JSON NULL AFTER step_passed;
