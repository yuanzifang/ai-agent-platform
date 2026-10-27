-- 写工具逐次审批：写操作先落审批，批准后才执行。
CREATE TABLE tool_approval (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    agent_id      BIGINT       NOT NULL,
    tool_id       BIGINT       NOT NULL,
    tool_key      VARCHAR(128) NULL,
    tool_name     VARCHAR(256) NULL,
    tool_type     VARCHAR(32)  NULL,
    tool_input    TEXT         NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    requested_by  BIGINT       NULL,
    decided_by    BIGINT       NULL,
    comment       VARCHAR(1000) NULL,
    result        TEXT         NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at    DATETIME     NULL,
    KEY idx_tool_approval_tenant_status (tenant_id, status, id),
    KEY idx_tool_approval_agent (agent_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
