-- 黄金集与普通审核集分层：APPROVED != GOLDEN
ALTER TABLE test_case
    ADD COLUMN dataset_tier VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE' AFTER status,
    ADD COLUMN risk_level VARCHAR(16) NULL AFTER dataset_tier,
    ADD COLUMN coverage_tags JSON NULL AFTER risk_level,
    ADD COLUMN expires_at DATETIME NULL AFTER coverage_tags,
    ADD KEY idx_case_tier (tenant_id, dataset_tier, status);

UPDATE test_case SET dataset_tier = CASE
    WHEN status = 'APPROVED' THEN 'APPROVED'
    WHEN status = 'ARCHIVED' THEN 'ARCHIVED'
    ELSE 'CANDIDATE' END;
