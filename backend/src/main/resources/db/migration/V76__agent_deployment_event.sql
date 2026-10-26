-- Agent 容器部署事件（部署/启动/停止/删除，用于历史与审计）
CREATE TABLE IF NOT EXISTS agent_deployment_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id       BIGINT       NOT NULL,
    deployment_id  BIGINT       NULL,
    tenant_id      BIGINT       NOT NULL,
    action         VARCHAR(16)  NOT NULL COMMENT 'DEPLOY/START/STOP/REMOVE',
    status         VARCHAR(16)  NULL COMMENT 'SUCCESS/FAILED',
    version        VARCHAR(32)  NULL,
    container_name VARCHAR(128) NULL,
    host_port      INT          NULL,
    message        VARCHAR(1000) NULL,
    operator_id    BIGINT       NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ade_agent (agent_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
