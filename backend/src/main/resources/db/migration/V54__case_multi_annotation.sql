-- 多标注：每条回归明细允许多个 reviewer 独立标注；item 上保存共识/均值与标注数
ALTER TABLE test_case_run_item ADD COLUMN label_count INT NOT NULL DEFAULT 0 AFTER review_note;

CREATE TABLE case_calibration_label (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    run_item_id BIGINT       NOT NULL,
    reviewer_id BIGINT       NOT NULL,
    human_score DECIMAL(4,3) NULL,
    human_label VARCHAR(16)  NOT NULL,
    note        VARCHAR(512) NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_cal_item_reviewer (run_item_id, reviewer_id),
    KEY idx_case_cal_tenant (tenant_id, run_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
