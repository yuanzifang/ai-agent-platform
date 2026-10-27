-- Agent 只引用平台维护的检索资源组合，不保存连接地址和密码。
CREATE TABLE retrieval_resource_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    search_backend VARCHAR(16) NOT NULL DEFAULT 'MYSQL',
    vector_backend VARCHAR(16) NOT NULL DEFAULT 'PGVECTOR',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_resource_profile_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台检索资源组合';

ALTER TABLE agent_registry
    ADD COLUMN resource_profile_id BIGINT NULL AFTER retrieval_config;
