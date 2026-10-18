-- P1 MCP Server 管理（需求 3.15、数据字典 6.47）；鉴权信息由应用层 AES-GCM 加密存储
CREATE TABLE IF NOT EXISTS mcp_server (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    endpoint VARCHAR(256) NOT NULL,
    auth_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    encrypted_auth TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    health_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    last_sync_at DATETIME NULL,
    last_checked_at DATETIME NULL,
    tenant_id BIGINT NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_mcp_server_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
