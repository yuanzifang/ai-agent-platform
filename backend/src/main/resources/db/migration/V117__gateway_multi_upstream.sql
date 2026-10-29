-- 网关多上游：除 target/fallback 外支持最多 5 个额外上游。
ALTER TABLE gateway_route
    ADD COLUMN upstream_urls TEXT NULL AFTER fallback_url;
