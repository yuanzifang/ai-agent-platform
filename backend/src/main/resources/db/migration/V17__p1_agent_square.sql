-- P1 Agent 广场（需求 3.8）：上架标记 + 收藏 + 评分/回复 + 举报
ALTER TABLE agent_registry
    ADD COLUMN listed TINYINT NOT NULL DEFAULT 1 AFTER visibility;

CREATE TABLE IF NOT EXISTS agent_favorite (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_favorite (agent_id, user_id),
    KEY idx_agent_favorite_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_rating (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    score TINYINT NOT NULL,
    comment VARCHAR(1000) NULL,
    reply VARCHAR(1000) NULL,
    reply_by BIGINT NULL,
    reply_at DATETIME NULL,
    tenant_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_rating (agent_id, user_id),
    KEY idx_agent_rating_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    tenant_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_report_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
