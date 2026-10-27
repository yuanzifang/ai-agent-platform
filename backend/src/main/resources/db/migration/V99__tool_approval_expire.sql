ALTER TABLE tool_approval
    ADD COLUMN expire_at DATETIME NULL AFTER decided_at,
    ADD KEY idx_tool_approval_expire (status, expire_at);
