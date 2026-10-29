-- 网关主动健康检查：按路由配置探测上游路径；关闭时不影响代理。
ALTER TABLE gateway_route
    ADD COLUMN health_path VARCHAR(512) NULL AFTER rate_limit_per_min,
    ADD COLUMN health_check_enabled TINYINT NOT NULL DEFAULT 1 AFTER health_path;
