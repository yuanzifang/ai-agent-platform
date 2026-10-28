-- Agent 独立部署资源 Profile：Docker 运行时资源上限与副本范围。
CREATE TABLE agent_deploy_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    cpu_limit VARCHAR(32) NOT NULL DEFAULT '1',
    memory_limit VARCHAR(32) NOT NULL DEFAULT '512m',
    pids_limit INT NOT NULL DEFAULT 256,
    default_replicas INT NOT NULL DEFAULT 1,
    max_replicas INT NOT NULL DEFAULT 5,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_agent_deploy_profile_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 独立部署资源 Profile';

INSERT INTO agent_deploy_profile
    (name, description, cpu_limit, memory_limit, pids_limit, default_replicas, max_replicas,
     status, created_at, updated_at)
VALUES
    ('标准部署', '默认 1 CPU / 512m / 256 PID，最多 5 副本', '1', '512m', 256, 1, 5,
     'ENABLED', NOW(), NOW())
ON DUPLICATE KEY UPDATE name = name;

ALTER TABLE agent_deployment
    ADD COLUMN deploy_profile_id BIGINT NULL AFTER image;
