-- 线上对话用例库：经典案例/坏例沉淀，供模型切换回归
CREATE TABLE IF NOT EXISTS test_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    agent_id BIGINT NULL,
    conversation_id BIGINT NULL,
    user_message_id BIGINT NULL,
    assistant_message_id BIGINT NULL,
    question TEXT NOT NULL,
    actual_answer TEXT NULL,
    expected_answer TEXT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'BAD' COMMENT 'CLASSIC/BAD',
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AUTO_CLASSIC/AUTO_BAD',
    status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/APPROVED/ARCHIVED',
    tags JSON NULL,
    note VARCHAR(512) NULL,
    model_name VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_case_assistant_msg (assistant_message_id),
    INDEX idx_case_tenant_status (tenant_id, status),
    INDEX idx_case_agent (tenant_id, agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话用例库';

CREATE TABLE IF NOT EXISTS test_case_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NULL,
    model_name VARCHAR(64) NOT NULL,
    judge_model VARCHAR(64) NULL,
    total INT NOT NULL DEFAULT 0,
    passed INT NOT NULL DEFAULT 0,
    avg_score DECIMAL(6,4) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例回归运行';

CREATE TABLE IF NOT EXISTS test_case_run_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NULL,
    score DECIMAL(6,4) NULL,
    passed TINYINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_case_run_items (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用例回归明细';
