-- 计费成本口径修复：历史空成本归零 + 列默认值 + 内置模型示例单价（可在模型接入页调整）
UPDATE billing_usage SET cost = 0.000000 WHERE cost IS NULL;
ALTER TABLE billing_usage MODIFY cost DECIMAL(12,6) NOT NULL DEFAULT 0.000000;

-- 示例单价：DeepSeek 输入 2 元/百万 Token、输出 8 元/百万 Token（元/千 Token：0.002 / 0.008）
UPDATE model SET input_price = 0.002000, output_price = 0.008000
WHERE model_name IN ('deepseek-flash', 'deepseek-v4-pro') AND input_price IS NULL;
