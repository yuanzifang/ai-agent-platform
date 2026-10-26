-- Agent 容器部署（MVP：本机 Docker，一个 Agent 一个运行时容器）
CREATE TABLE IF NOT EXISTS agent_deployment (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id       BIGINT       NOT NULL,
    tenant_id      BIGINT       NOT NULL,
    version        VARCHAR(32)  NULL COMMENT '部署时的 Agent 版本号',
    container_name VARCHAR(128) NOT NULL,
    container_id   VARCHAR(128) NULL,
    image          VARCHAR(255) NOT NULL,
    host_port      INT          NOT NULL,
    endpoint       VARCHAR(512) NULL COMMENT '对外 OpenAI 兼容地址（含 /v1）',
    local_endpoint VARCHAR(512) NULL COMMENT '本机地址（localhost）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/STOPPED/FAILED',
    api_key_id     BIGINT       NULL COMMENT '容器内置的集成 API Key（删除部署时一并清理）',
    last_error     VARCHAR(1000) NULL,
    deployed_by    BIGINT       NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_deployment_agent (agent_id),
    KEY idx_agent_deployment_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
