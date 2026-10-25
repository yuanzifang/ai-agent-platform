-- 租户月度金额上限（评审：Token 数无法跨模型比较，金额才是统一度量；
-- 累计总额限制由预付费余额承担，不重复设计）
ALTER TABLE sys_tenant_quota ADD COLUMN monthly_cost_limit DECIMAL(12,2) NULL COMMENT '月度金额上限（元），NULL=不限';
