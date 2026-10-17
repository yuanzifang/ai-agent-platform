-- P1 Agent 评测（需求 3.5/6.8–6.11 + 评测执行流程）
CREATE TABLE eval_dataset (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    agent_id    BIGINT       NULL,
    tenant_id   BIGINT       NOT NULL,
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE eval_dataset_item (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id     BIGINT  NOT NULL,
    question       TEXT    NOT NULL,
    expected_answer TEXT   NULL,
    score_criteria TEXT    NULL,
    tenant_id      BIGINT  NOT NULL,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_item_ds (dataset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE eval_task (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    agent_id         BIGINT       NOT NULL,
    agent_version    VARCHAR(32)  NULL,
    target_type      VARCHAR(16)  NOT NULL DEFAULT 'AGENT',
    eval_type        VARCHAR(16)  NOT NULL DEFAULT 'ONCE',
    filter_snapshot  JSON         NULL,
    matched_traces   INT          NULL,
    judge_model      VARCHAR(64)  NOT NULL,
    metrics          JSON         NULL,
    rounds           INT          NOT NULL DEFAULT 1,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    estimated_calls  INT          NULL,
    completed_calls  INT          NULL,
    avg_score        DECIMAL(6,4) NULL,
    pass_rate        DECIMAL(6,4) NULL,
    dataset_id       BIGINT       NULL,
    tenant_id        BIGINT       NOT NULL,
    created_by       BIGINT       NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_eval_task_agent (agent_id, tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE eval_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    dataset_item_id BIGINT       NOT NULL,
    trace_id        VARCHAR(64)  NULL,
    metric_key      VARCHAR(64)  NOT NULL DEFAULT 'correctness',
    answer          TEXT         NULL,
    score           DECIMAL(6,4) NULL,
    passed          TINYINT      NULL,
    judge_reason    TEXT         NULL,
    latency_ms      BIGINT       NULL,
    tokens          INT          NULL,
    tenant_id       BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_eval_result_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
