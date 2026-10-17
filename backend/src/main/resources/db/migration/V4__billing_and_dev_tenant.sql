-- M2 补充：计费用量（需求文档 6.12）+ 开发租户与初始用户（架构 10.8 dev 环境示例）

CREATE TABLE billing_usage (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    agent_id          BIGINT       NULL,
    knowledge_base_id BIGINT       NULL,
    source            VARCHAR(16)  NOT NULL,
    model_name        VARCHAR(64)  NULL,
    input_tokens      BIGINT       NOT NULL DEFAULT 0,
    output_tokens     BIGINT       NOT NULL DEFAULT 0,
    cost              DECIMAL(12,6) NULL,
    stat_date         DATE         NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_usage_tenant_date (tenant_id, stat_date),
    KEY idx_usage_agent (agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 开发租户（正式环境可经 POST /api/v1/tenant 开通）
INSERT INTO sys_tenant (tenant_key, name, status, created_at)
VALUES ('dev', '开发租户', 'ACTIVE', NOW());

INSERT INTO sys_tenant_quota (tenant_id) VALUES (1);

-- 开发账号 dev：密码占位，由 DevAccountInitializer 设为 Dev@123456（仅占位存在时）
INSERT INTO sys_user (username, password_hash, display_name, is_platform_admin, is_service_account,
                      tenant_id, status, created_at)
VALUES ('dev', '__RESET__', '开发用户', 0, 0, 1, 'ENABLED', NOW());

INSERT INTO sys_user_role (user_id, role_id) VALUES (2, 1);  -- OWNER

-- 租户默认模型配置：指向平台预置 DeepSeek
INSERT INTO sys_tenant_config (tenant_id, config_key, config_value)
VALUES (1, 'default_chat_model', JSON_OBJECT('providerId', 1, 'modelName', 'deepseek-flash')),
       (1, 'default_embedding_model', JSON_OBJECT('provider', 'ollama', 'modelName', 'nomic-embed-text'));
