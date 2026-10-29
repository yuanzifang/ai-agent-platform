-- 网关请求体 JSON Schema（可选）；空=不校验。
ALTER TABLE gateway_route
    ADD COLUMN request_schema TEXT NULL AFTER signing_secret;
