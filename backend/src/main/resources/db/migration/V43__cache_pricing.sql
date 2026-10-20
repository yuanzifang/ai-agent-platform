-- 分项计价：缓存命中/写入与推理 Token（OpenAI/Anthropic usage 通用字段；缺失时为 0）
ALTER TABLE billing_usage
    ADD COLUMN cached_input_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '缓存命中输入 Token' AFTER output_tokens,
    ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '缓存写入 Token' AFTER cached_input_tokens,
    ADD COLUMN reasoning_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '推理 Token' AFTER cache_write_tokens;

ALTER TABLE billing_rule
    ADD COLUMN cache_read_price DECIMAL(12,6) NULL COMMENT '缓存命中 元/千Token' AFTER output_price,
    ADD COLUMN cache_write_price DECIMAL(12,6) NULL COMMENT '缓存写入 元/千Token' AFTER cache_read_price;

ALTER TABLE model
    ADD COLUMN cache_read_price DECIMAL(12,6) NULL COMMENT '缓存命中 元/千Token' AFTER output_price,
    ADD COLUMN cache_write_price DECIMAL(12,6) NULL COMMENT '缓存写入 元/千Token' AFTER cache_read_price;

-- 示例：DeepSeek 缓存命中按输入价 10% 计（可在模型接入/计费规则页调整）
UPDATE model SET cache_read_price = ROUND(input_price * 0.1, 6), cache_write_price = 0
WHERE model_name IN ('deepseek-flash', 'deepseek-v4-pro') AND cache_read_price IS NULL;
