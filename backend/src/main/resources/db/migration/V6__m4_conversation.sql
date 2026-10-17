-- M4 会话对话（需求文档 6.18–6.25）：会话/消息/附件/审核留痕/反馈/分享

CREATE TABLE conversation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id     VARCHAR(64)  NOT NULL,
    agent_id       BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    title          VARCHAR(128) NOT NULL DEFAULT '新会话',
    source         VARCHAR(16)  NOT NULL DEFAULT 'web',
    last_active_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_id      BIGINT       NOT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session (session_id),
    KEY idx_conv_tenant_user (tenant_id, user_id, last_active_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE message (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id    BIGINT       NOT NULL,
    trace_id           VARCHAR(64)  NULL,
    role               VARCHAR(16)  NOT NULL,
    parent_message_id  BIGINT       NULL,
    is_regenerated     TINYINT      NOT NULL DEFAULT 0,
    content            TEXT         NOT NULL,
    attachments        JSON         NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    audited_status     VARCHAR(16)  NOT NULL DEFAULT 'PASS',
    is_ai_generated    TINYINT      NOT NULL DEFAULT 0,
    tokens             INT          NOT NULL DEFAULT 0,
    cost               DECIMAL(12,6) NULL,
    tenant_id          BIGINT       NOT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_msg_conv (conversation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE chat_attachment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT       NOT NULL,
    message_id      BIGINT       NULL,
    object_name     VARCHAR(512) NOT NULL,
    file_name       VARCHAR(256) NOT NULL,
    size_bytes      BIGINT       NOT NULL DEFAULT 0,
    content_type    VARCHAR(64)  NULL,
    tenant_id       BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_attach_conv (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE moderation_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    direction       VARCHAR(8)  NOT NULL,
    trace_id        VARCHAR(64) NULL,
    message_id      BIGINT      NULL,
    conversation_id BIGINT      NULL,
    rule_id         VARCHAR(64) NOT NULL,
    action          VARCHAR(16) NOT NULL,
    matched_text    TEXT        NULL,
    tenant_id       BIGINT      NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_mod_msg (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE feedback (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id      BIGINT      NOT NULL,
    conversation_id BIGINT      NOT NULL,
    trace_id        VARCHAR(64) NULL,
    user_id         BIGINT      NOT NULL,
    type            VARCHAR(8)  NOT NULL,
    comment         VARCHAR(512) NULL,
    tenant_id       BIGINT      NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_fb_msg (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE conversation_share (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT      NOT NULL,
    token           VARCHAR(64) NOT NULL,
    expires_at      DATETIME    NOT NULL,
    created_by      BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_share_token (token),
    KEY idx_share_conv (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
