-- 调用方 Key 请求体上限；0/NULL=沿用全局 5MB。
ALTER TABLE gateway_api_key
    ADD COLUMN max_body_bytes INT NULL AFTER daily_quota;
