-- P1 Agent 凭证（需求 3.2(6)、数据字典 6.15）：AES-GCM 加密，运行时注入工具调用
CREATE TABLE IF NOT EXISTS agent_credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,               -- API_KEY / OAUTH / BASIC
    encrypted_content TEXT NOT NULL,
    tool_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    tenant_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_credential_agent (agent_id, status),
    KEY idx_agent_credential_tool (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
