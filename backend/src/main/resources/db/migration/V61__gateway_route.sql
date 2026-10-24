CREATE TABLE gateway_route (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    name                VARCHAR(128) NOT NULL,
    route_key           VARCHAR(64)  NOT NULL,
    api_key_hash        VARCHAR(128) NOT NULL,
    api_key_prefix      VARCHAR(16)  NULL,
    target_url          VARCHAR(512) NOT NULL,
    timeout_ms          INT          NOT NULL DEFAULT 15000,
    retries             INT          NOT NULL DEFAULT 0,
    rate_limit_per_min  INT          NOT NULL DEFAULT 60,
    enabled             TINYINT      NOT NULL DEFAULT 1,
    created_by          BIGINT       NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gateway_route_key (route_key),
    KEY idx_gateway_tenant (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE gateway_request_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    route_id       BIGINT       NOT NULL,
    method         VARCHAR(16)  NOT NULL,
    path           VARCHAR(512) NOT NULL,
    status         INT          NOT NULL,
    upstream_status INT         NULL,
    latency_ms     BIGINT       NOT NULL DEFAULT 0,
    error          VARCHAR(512) NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_gateway_log_route (route_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
