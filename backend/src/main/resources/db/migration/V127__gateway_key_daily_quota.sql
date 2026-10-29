-- 调用方 Key 每日调用配额；0/NULL=不限制。
ALTER TABLE gateway_api_key
    ADD COLUMN daily_quota INT NULL AFTER rate_limit_per_min;
