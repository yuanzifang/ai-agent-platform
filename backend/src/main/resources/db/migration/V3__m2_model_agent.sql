-- M2 模型与 Agent：Provider/Model（需求文档 6.35/6.38/6.39）+ Agent 主表与版本（6.3/6.4）+ 编辑态关系表（6.41）
-- 绑定表本里程碑建表备用，工具/知识库/子Agent 面板随 M3+ 启用

CREATE TABLE provider (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    provider_type VARCHAR(32)  NOT NULL,
    base_url      VARCHAR(256) NOT NULL,
    proxy         VARCHAR(128) NULL,
    timeout_ms    INT          NOT NULL DEFAULT 60000,
    retry_times   INT          NOT NULL DEFAULT 2,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    tenant_id     BIGINT       NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_provider_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE provider_credential (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id       BIGINT       NOT NULL,
    name              VARCHAR(64)  NOT NULL,
    encrypted_content TEXT         NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    tenant_id         BIGINT       NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_cred_provider (provider_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE model (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id    BIGINT       NOT NULL,
    model_name     VARCHAR(128) NOT NULL,
    display_name   VARCHAR(128) NULL,
    purpose        VARCHAR(16)  NOT NULL DEFAULT 'CHAT',
    capabilities    JSON         NULL,
    default_params JSON         NULL,
    input_price     DECIMAL(12,6) NULL,
    output_price    DECIMAL(12,6) NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    tenant_id      BIGINT       NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_model_provider (provider_id),
    KEY idx_model_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_registry (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_key         VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    description       VARCHAR(512) NULL,
    avatar_url        VARCHAR(512) NULL,
    category          VARCHAR(64)  NULL,
    tags              JSON         NULL,
    system_prompt     TEXT         NULL,
    opening_statement TEXT         NULL,
    agent_capabilities JSON        NULL,
    skills            JSON         NULL,
    memory_config     JSON         NULL,
    model_config      JSON         NULL,
    tools             JSON         NULL,
    knowledge_base_ids JSON        NULL,
    retrieval_config  JSON         NULL,
    sub_agent_ids     JSON         NULL,
    variables         JSON         NULL,
    owner_id          BIGINT       NOT NULL,
    visibility        VARCHAR(16)  NOT NULL DEFAULT 'TENANT',
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    tenant_id         BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_key (agent_key),
    KEY idx_agent_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_version (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id   BIGINT      NOT NULL,
    version    VARCHAR(32) NOT NULL,
    snapshot   JSON        NOT NULL,
    created_by BIGINT      NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id  BIGINT      NOT NULL,
    KEY idx_agent_version (agent_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_tool_binding (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id   BIGINT  NOT NULL,
    tool_id    BIGINT  NOT NULL,
    params     JSON    NULL,
    enabled    TINYINT NOT NULL DEFAULT 1,
    sort_order INT     NOT NULL DEFAULT 0,
    tenant_id  BIGINT  NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_tool (agent_id, tool_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_skill_binding (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id   BIGINT  NOT NULL,
    skill_id   BIGINT  NOT NULL,
    enabled    TINYINT NOT NULL DEFAULT 1,
    sort_order INT     NOT NULL DEFAULT 0,
    tenant_id  BIGINT  NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_skill (agent_id, skill_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_knowledge_base (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id          BIGINT  NOT NULL,
    knowledge_base_id BIGINT  NOT NULL,
    enabled           TINYINT NOT NULL DEFAULT 1,
    retrieval_config  JSON    NULL,
    tenant_id         BIGINT  NOT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_kb (agent_id, knowledge_base_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_sub_agent (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT  NOT NULL,
    sub_agent_id BIGINT  NOT NULL,
    enabled      TINYINT NOT NULL DEFAULT 1,
    sort_order   INT     NOT NULL DEFAULT 0,
    tenant_id    BIGINT  NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_sub (agent_id, sub_agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_variable (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT  NOT NULL,
    var_key      VARCHAR(64) NOT NULL,
    var_type     VARCHAR(16) NOT NULL DEFAULT 'STRING',
    default_value VARCHAR(512) NULL,
    required     TINYINT NOT NULL DEFAULT 0,
    description  VARCHAR(256) NULL,
    tenant_id    BIGINT  NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_var (agent_id, var_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 种子：DeepSeek 平台预置 Provider（tenant_id 为空，全租户可见）；模型经 /provider/{id}/models/sync 同步或手工预置
INSERT INTO provider (name, provider_type, base_url, timeout_ms, retry_times, status, tenant_id)
VALUES ('DeepSeek', 'DEEPSEEK', 'https://api.deepseek.com', 60000, 2, 'ENABLED', NULL);

-- 预置两个模型（同步接口亦可刷新）；DeepSeek 价格为 0（计费 M5 接 billing_rule）
INSERT INTO model (provider_id, model_name, display_name, purpose, capabilities, default_params, status, tenant_id)
VALUES (1, 'deepseek-flash', 'DeepSeek V4.1 Flash', 'CHAT',
        JSON_OBJECT('multimodal', true, 'reasoning', true, 'functionCall', true),
        JSON_OBJECT('temperature', 0.7), 'ENABLED', NULL),
       (1, 'deepseek-v4-pro', 'DeepSeek V4 Pro', 'CHAT',
        JSON_OBJECT('multimodal', false, 'reasoning', true, 'functionCall', true),
        JSON_OBJECT('temperature', 0.7), 'ENABLED', NULL);
