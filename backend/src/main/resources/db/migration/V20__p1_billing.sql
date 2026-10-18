-- P1 计费管理（需求 3.11）：计费规则 + 账户/充值流水 + 月账单
CREATE TABLE IF NOT EXISTS billing_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    model_name VARCHAR(64) NULL,             -- NULL = 租户默认规则
    billing_mode VARCHAR(16) NOT NULL DEFAULT 'TOKEN',  -- TOKEN / COUNT
    input_price DECIMAL(14,6) NOT NULL DEFAULT 0,       -- 元 / 千 Token
    output_price DECIMAL(14,6) NOT NULL DEFAULT 0,      -- 元 / 千 Token
    price_per_call DECIMAL(14,6) NOT NULL DEFAULT 0,    -- COUNT 模式：元 / 次
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_billing_rule_tenant (tenant_id, model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS billing_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    balance DECIMAL(16,4) NOT NULL DEFAULT 0,   -- 累计充值/调整金额（可用余额 = balance - 已消耗）
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_account_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS billing_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,                 -- RECHARGE / ADJUST
    amount DECIMAL(16,4) NOT NULL,
    balance_after DECIMAL(16,4) NOT NULL,
    operator_id BIGINT NULL,
    note VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_billing_tx_tenant (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS billing_bill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    period VARCHAR(7) NOT NULL,                -- yyyy-MM
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    calls BIGINT NOT NULL DEFAULT 0,
    cost DECIMAL(16,4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'GENERATED',
    generated_by BIGINT NULL,
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_bill_period (tenant_id, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
