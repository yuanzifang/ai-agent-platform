ALTER TABLE gateway_route
    ADD COLUMN fallback_url VARCHAR(512) NULL AFTER target_url;
