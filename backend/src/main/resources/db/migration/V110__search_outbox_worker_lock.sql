-- 多副本 outbox 认领：worker 锁超时后其他副本可接管。
ALTER TABLE knowledge_search_outbox
    ADD COLUMN locked_by VARCHAR(128) NULL AFTER last_error,
    ADD COLUMN locked_until DATETIME NULL AFTER locked_by,
    ADD KEY idx_search_outbox_claim (status, available_at, locked_until);
