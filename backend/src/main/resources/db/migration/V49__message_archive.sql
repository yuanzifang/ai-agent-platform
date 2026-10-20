-- 消息归档表（架构 8.4：message 默认保留 90 天，过期归档）
-- 保留原 message 主键/字段，额外记录 archived_at；不参与月分区，便于长期查询/导出。
CREATE TABLE message_archive (
    id                BIGINT       NOT NULL,
    conversation_id   BIGINT       NOT NULL,
    trace_id          VARCHAR(64)  NULL,
    role              VARCHAR(16)  NOT NULL,
    parent_message_id BIGINT       NULL,
    is_regenerated    TINYINT      NOT NULL DEFAULT 0,
    content           TEXT         NOT NULL,
    attachments       JSON         NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    audited_status    VARCHAR(16)  NOT NULL DEFAULT 'PASS',
    is_ai_generated   TINYINT      NOT NULL DEFAULT 0,
    tokens            INT          NOT NULL DEFAULT 0,
    cost              DECIMAL(12,6) NULL,
    tenant_id         BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL,
    archived_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    KEY idx_msg_archive_conv (conversation_id, created_at),
    KEY idx_msg_archive_tenant (tenant_id, created_at),
    KEY idx_msg_archive_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
