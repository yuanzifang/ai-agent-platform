-- 调用方 Key 方法/路径 scope；空=不限制。
ALTER TABLE gateway_api_key
    ADD COLUMN allowed_methods VARCHAR(255) NULL AFTER rate_limit_per_min,
    ADD COLUMN allowed_paths TEXT NULL AFTER allowed_methods;
