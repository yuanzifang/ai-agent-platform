-- P1 定时调度（需求 3.2(5)、数据字典 6.16）
CREATE TABLE IF NOT EXISTS agent_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    cron_expr VARCHAR(64) NOT NULL,
    input_params JSON NULL,
    timeout_ms INT NOT NULL DEFAULT 60000,
    notify_type VARCHAR(16) NOT NULL DEFAULT 'STORE',   -- STORE / WEBHOOK
    webhook_url VARCHAR(512) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    next_run_at DATETIME NULL,
    last_run_at DATETIME NULL,
    last_status VARCHAR(16) NULL,                      -- SUCCESS / FAILED
    last_message VARCHAR(1000) NULL,
    tenant_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_schedule_due (status, next_run_at),
    KEY idx_schedule_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_schedule_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    trace_id VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL,
    answer TEXT NULL,
    error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_schedule_result (schedule_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
