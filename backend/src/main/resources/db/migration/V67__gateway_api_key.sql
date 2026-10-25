-- 网关调用方 API Key（评审缺口）：多调用方、独立禁用/过期/按 Key 限流与统计
CREATE TABLE gateway_api_key (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT      NOT NULL,
    route_id            BIGINT      NOT NULL,
    name                VARCHAR(128) NOT NULL COMMENT '调用方名称',
    key_hash            VARCHAR(128) NOT NULL,
    key_prefix          VARCHAR(16)  NOT NULL,
    enabled             TINYINT      NOT NULL DEFAULT 1,
    expires_at          DATETIME     NULL COMMENT '空=永不过期',
    rate_limit_per_min  INT          NULL COMMENT '空=用路由级限流',
    request_count       BIGINT       NOT NULL DEFAULT 0,
    last_used_at        DATETIME     NULL,
    created_by          BIGINT       NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_hash (key_hash),
    KEY idx_route (route_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE gateway_request_log ADD COLUMN api_key_id BIGINT NULL, ADD KEY idx_log_key (api_key_id);
