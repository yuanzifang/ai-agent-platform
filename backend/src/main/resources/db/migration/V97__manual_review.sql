ALTER TABLE knowledge_document
    ADD COLUMN manual_review_status VARCHAR(16) NULL COMMENT 'PENDING/RESOLVED' AFTER parse_warning,
    ADD COLUMN manual_reviewed_at DATETIME NULL AFTER manual_review_status,
    ADD COLUMN manual_reviewed_by BIGINT NULL AFTER manual_reviewed_at,
    ADD COLUMN manual_review_note VARCHAR(512) NULL AFTER manual_reviewed_by,
    ADD KEY idx_doc_manual_review (tenant_id, manual_review_status);
