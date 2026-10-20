-- 长期记忆：用户可见、可编辑的事实列表（需求 3.29 P2）
CREATE TABLE IF NOT EXISTS user_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NULL COMMENT 'NULL=适用于所有 Agent',
    category VARCHAR(32) NOT NULL DEFAULT 'FACT',
    content VARCHAR(2000) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_memory_user_agent (tenant_id, user_id, agent_id),
    INDEX idx_memory_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期记忆（用户可编辑事实）';
