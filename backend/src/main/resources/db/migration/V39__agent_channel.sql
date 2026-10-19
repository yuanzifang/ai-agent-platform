-- Agent 渠道（外部消息接入 + 出站回复）
CREATE TABLE IF NOT EXISTS agent_channel (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    token VARCHAR(64) NOT NULL,
    outbound_url VARCHAR(512) NULL,
    secret VARCHAR(512) NULL,
    config_json MEDIUMTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_channel_token (token),
    KEY idx_agent_channel_agent (agent_id),
    KEY idx_agent_channel_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS channel_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    external_user_id VARCHAR(128) NULL,
    session_id VARCHAR(128) NULL,
    content MEDIUMTEXT NULL,
    status VARCHAR(16) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_channel_message (channel_id, created_at),
    KEY idx_channel_message_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
