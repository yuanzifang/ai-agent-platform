-- P0/P1 集成调用：Agent 绑定 API Key（需求 3.2(4)、数据字典 6.14）
CREATE TABLE IF NOT EXISTS agent_integration_api_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL DEFAULT 'API',        -- WEB / API / THIRD_PARTY
    key_hash CHAR(64) NOT NULL,
    key_prefix VARCHAR(24) NOT NULL,
    service_user_id BIGINT NULL,                       -- 对应服务账号（is_service_account=1）
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    daily_limit INT NOT NULL DEFAULT 0,                -- 0=不限
    minute_limit INT NOT NULL DEFAULT 0,
    last_used_at DATETIME NULL,
    tenant_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_hash (key_hash),
    KEY idx_api_key_agent (agent_id),
    KEY idx_api_key_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE conversation
    ADD COLUMN external_user_id VARCHAR(128) NULL AFTER user_id;
