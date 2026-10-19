-- Agent 工作流与人工审批（HITL）
CREATE TABLE IF NOT EXISTS agent_workflow (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    definition MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_workflow_agent (agent_id),
    KEY idx_agent_workflow_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    definition MEDIUMTEXT NOT NULL,
    input_text TEXT NULL,
    output_text MEDIUMTEXT NULL,
    context_json MEDIUMTEXT NULL,
    status VARCHAR(24) NOT NULL,
    current_node VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    user_id BIGINT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_workflow_run_agent (agent_id, created_at),
    KEY idx_workflow_run_tenant (tenant_id, created_at),
    KEY idx_workflow_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_node_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_type VARCHAR(24) NOT NULL,
    name VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL,
    input_text TEXT NULL,
    output_text MEDIUMTEXT NULL,
    error_message VARCHAR(1000) NULL,
    started_at DATETIME NULL,
    ended_at DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_workflow_node_run (run_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_approval (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    payload MEDIUMTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    comment VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_workflow_approval_status (tenant_id, status, created_at),
    KEY idx_workflow_approval_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
