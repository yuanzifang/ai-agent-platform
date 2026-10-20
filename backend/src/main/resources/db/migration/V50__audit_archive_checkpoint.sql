-- 审计保留：归档旧分区，并记录每个租户的链锚点，保证裁剪后 verifyChain 仍可校验
CREATE TABLE audit_operation_archive (
    id           BIGINT      NOT NULL,
    tenant_id    BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    op_type      VARCHAR(64) NOT NULL,
    object_type  VARCHAR(32) NOT NULL,
    object_id    VARCHAR(64) NOT NULL,
    op_result    VARCHAR(16) NOT NULL,
    detail       JSON        NULL,
    ip           VARCHAR(64) NULL,
    prev_hash    VARCHAR(64) NULL,
    hash_version TINYINT     NOT NULL DEFAULT 1,
    created_at   DATETIME    NOT NULL,
    archived_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at),
    KEY idx_audit_archive_tenant_time (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 裁剪审计分区前写入。verifyChain 只采用“对应分区已经不存在”的最新锚点；
-- 这样 DROP PARTITION 失败/进程中断时，仍能退回到上一个有效锚点。
CREATE TABLE audit_chain_checkpoint (
    tenant_id        BIGINT      NOT NULL,
    partition_name   VARCHAR(16) NOT NULL,
    last_removed_id  BIGINT      NOT NULL,
    last_removed_hash VARCHAR(64) NOT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, partition_name),
    KEY idx_audit_checkpoint_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
