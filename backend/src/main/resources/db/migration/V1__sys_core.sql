-- M1 平台基座：租户/用户/角色权限/会话/审计/平台参数（需求文档 6.1、6.2、6.27、6.31、6.33、6.37、6.40、6.49、6.50）

CREATE TABLE sys_tenant (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key  VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'TRIAL',
    config_ref  VARCHAR(64)  NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_key (tenant_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_tenant_config (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    config_key   VARCHAR(128) NOT NULL,
    config_value JSON         NULL,
    description  VARCHAR(256) NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_config (tenant_id, config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_tenant_quota (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id            BIGINT      NOT NULL,
    max_agents           INT         NOT NULL DEFAULT 10,
    max_knowledge_bases  INT         NOT NULL DEFAULT 5,
    max_tokens_per_day   BIGINT      NOT NULL DEFAULT 1000000,
    max_storage_bytes    BIGINT      NOT NULL DEFAULT 10737418240,
    max_api_calls_per_min INT        NOT NULL DEFAULT 60,
    created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quota_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_user (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    username            VARCHAR(64)  NOT NULL,
    password_hash       VARCHAR(128) NOT NULL,
    display_name        VARCHAR(64)  NULL,
    is_platform_admin   TINYINT      NOT NULL DEFAULT 0,
    is_service_account  TINYINT      NOT NULL DEFAULT 0,
    tenant_id           BIGINT       NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    last_login_at       DATETIME     NULL,
    locked_until        DATETIME     NULL,
    mfa_enabled         TINYINT      NOT NULL DEFAULT 0,
    mfa_secret          VARCHAR(128) NULL,
    password_updated_at DATETIME     NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_tenant_username (tenant_id, username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_role (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT      NULL,
    role_key   VARCHAR(64) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    is_builtin TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_role_key (tenant_id, role_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_permission (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    perm_key  VARCHAR(64) NOT NULL,
    name      VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_perm_key (perm_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_user_role (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_session (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    device             VARCHAR(128) NULL,
    ip                 VARCHAR(64)  NULL,
    expires_at         DATETIME     NOT NULL,
    revoked            TINYINT      NOT NULL DEFAULT 0,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_invitation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL,
    email      VARCHAR(128) NULL,
    role       VARCHAR(32)  NOT NULL,
    token      VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    invited_by BIGINT       NOT NULL,
    expires_at DATETIME     NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_invitation_token (token)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 审计：append-only + 哈希链（架构 8.4 分区约束：主键含分区列）
CREATE TABLE audit_operation (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    op_type     VARCHAR(64) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id   VARCHAR(64) NOT NULL,
    op_result   VARCHAR(16) NOT NULL,
    detail      JSON        NULL,
    ip          VARCHAR(64) NULL,
    prev_hash   VARCHAR(64) NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    KEY idx_audit_op_tenant_time (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
PARTITION BY RANGE COLUMNS (created_at) (
    PARTITION p2026 VALUES LESS THAN ('2027-01-01'),
    PARTITION p2027 VALUES LESS THAN ('2028-01-01'),
    PARTITION pmax VALUES LESS THAN (MAXVALUE)
);

CREATE TABLE audit_login (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT      NULL,
    user_id    BIGINT      NULL,
    result     VARCHAR(16) NOT NULL,
    ip         VARCHAR(64) NULL,
    user_agent VARCHAR(256) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_login_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sys_config (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL,
    config_value TEXT         NULL,
    description  VARCHAR(256) NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
