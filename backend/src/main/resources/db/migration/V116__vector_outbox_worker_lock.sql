-- pgvector 解析 outbox 的多副本 worker 锁。
ALTER TABLE knowledge_vector_outbox
    ADD COLUMN locked_by VARCHAR(128) NULL AFTER last_error,
    ADD COLUMN locked_until DATETIME NULL AFTER locked_by,
    ADD KEY idx_vector_outbox_claim (tenant_id, status, available_at, locked_until);
