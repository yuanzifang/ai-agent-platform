-- 人员维度配额（评审）：单成员每日 Token 上限 / 月度金额上限
-- 租户管理员在成员管理页配置；NULL = 不限（只受租户级配额约束）
CREATE TABLE user_quota (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    daily_token_limit   BIGINT       NULL,
    monthly_cost_limit  DECIMAL(12,2) NULL,
    enabled             TINYINT      NOT NULL DEFAULT 1,
    updated_by          BIGINT       NULL,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 用量明细落到人（人员维度统计与限流的数据基础；历史行为 NULL）
ALTER TABLE billing_usage ADD COLUMN user_id BIGINT NULL, ADD KEY idx_usage_user_date (user_id, stat_date);
