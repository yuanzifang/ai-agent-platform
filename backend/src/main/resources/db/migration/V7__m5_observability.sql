-- M5 可观测（需求 3.13/6.42）：Trace/Span 链路留痕
CREATE TABLE agent_trace (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NULL,
    conversation_id BIGINT       NULL,
    agent_id        BIGINT       NULL,
    user_id         BIGINT       NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    model_name      VARCHAR(64)  NULL,
    input_tokens    BIGINT       NOT NULL DEFAULT 0,
    output_tokens   BIGINT       NOT NULL DEFAULT 0,
    latency_ms      BIGINT       NOT NULL DEFAULT 0,
    tenant_id       BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trace (trace_id),
    KEY idx_trace_agent_tenant (agent_id, tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_span (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id      VARCHAR(64) NOT NULL,
    span_type     VARCHAR(16) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    input_tokens  BIGINT      NOT NULL DEFAULT 0,
    output_tokens BIGINT      NOT NULL DEFAULT 0,
    latency_ms    BIGINT      NOT NULL DEFAULT 0,
    attributes    JSON        NULL,
    tenant_id     BIGINT      NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_span_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
