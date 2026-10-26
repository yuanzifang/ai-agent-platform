-- 部署容器的调用方 Key（按 Key 日配额；容器内只注入哈希）
CREATE TABLE IF NOT EXISTS agent_runtime_key (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    name        VARCHAR(64) NOT NULL,
    key_hash    CHAR(64)    NOT NULL,
    key_prefix  VARCHAR(24) NOT NULL,
    daily_quota INT         NOT NULL DEFAULT 0 COMMENT '日调用配额，0=不限',
    status      VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_by  BIGINT      NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ark_hash (key_hash),
    KEY idx_ark_agent (agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
