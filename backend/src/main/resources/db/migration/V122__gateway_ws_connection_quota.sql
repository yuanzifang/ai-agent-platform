-- WebSocket 每路由最大并发连接数；0=不限制。
ALTER TABLE gateway_route
    ADD COLUMN ws_max_connections INT NOT NULL DEFAULT 100 AFTER health_check_enabled;
