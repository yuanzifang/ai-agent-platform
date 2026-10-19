-- 工作流版本发布/回滚
ALTER TABLE agent_workflow ADD COLUMN published_version VARCHAR(16) NULL AFTER status;
ALTER TABLE agent_workflow ADD COLUMN published_definition MEDIUMTEXT NULL AFTER published_version;

CREATE TABLE IF NOT EXISTS agent_workflow_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    version VARCHAR(16) NOT NULL,
    name VARCHAR(128) NOT NULL,
    definition MEDIUMTEXT NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_version (workflow_id, version),
    KEY idx_workflow_version_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
