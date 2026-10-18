-- M5 可观测补全（需求 3.4 / 架构 8.2/8.3）：Trace 字段扩展、Span 字段扩展、监控日汇总表

-- 1) agent_trace 扩展：KIND/NAME(agent_name)/VERSION/INPUT/OUTPUT/TOTAL TOKENS/COST/ERROR/TAGS
ALTER TABLE agent_trace
    ADD COLUMN agent_name    VARCHAR(128)  NULL COMMENT 'Agent 名称快照',
    ADD COLUMN version       VARCHAR(16)   NULL COMMENT '发布版本号（评测编辑态配置为空）',
    ADD COLUMN kind          VARCHAR(16)   NOT NULL DEFAULT 'internal' COMMENT 'internal/external/eval',
    ADD COLUMN input_text    TEXT          NULL COMMENT '用户输入',
    ADD COLUMN output_text   TEXT          NULL COMMENT 'Agent 输出',
    ADD COLUMN total_tokens  BIGINT        NOT NULL DEFAULT 0 COMMENT '输入+输出 Token',
    ADD COLUMN cost          DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '成本（model 单价 元/千 Token）',
    ADD COLUMN error_message VARCHAR(1000) NULL COMMENT '错误信息',
    ADD COLUMN tags          JSON          NULL COMMENT '标签（来源细分：调度/调试等）';

-- 回填历史数据
UPDATE agent_trace SET total_tokens = input_tokens + output_tokens WHERE total_tokens = 0;
UPDATE agent_trace SET kind = 'eval' WHERE conversation_id IS NULL;
UPDATE agent_trace t JOIN agent_registry a ON a.id = t.agent_id
SET t.agent_name = a.name WHERE t.agent_name IS NULL AND t.agent_id IS NOT NULL;

ALTER TABLE agent_trace
    ADD KEY idx_trace_tenant_agent_status (tenant_id, agent_id, created_at, status),
    ADD KEY idx_trace_session (session_id),
    ADD INDEX idx_trace_tags ((CAST(tags AS CHAR(64) ARRAY)));

-- 2) agent_span 扩展：span_id/parent_span_id/agent_id/start_time/end_time/duration_ms/model_name/tool_name
ALTER TABLE agent_span
    ADD COLUMN span_id        VARCHAR(64)  NULL COMMENT 'Span 唯一标识（应用层生成）',
    ADD COLUMN parent_span_id VARCHAR(64)  NULL COMMENT '父 Span（根为 NULL）',
    ADD COLUMN agent_id       BIGINT       NULL,
    ADD COLUMN start_time     DATETIME     NULL,
    ADD COLUMN end_time       DATETIME     NULL,
    ADD COLUMN duration_ms    BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时',
    ADD COLUMN model_name     VARCHAR(64)  NULL COMMENT 'LLM Span 模型名',
    ADD COLUMN tool_name      VARCHAR(128) NULL COMMENT 'Tool Span 工具名';

-- 回填历史数据：旧行 latency_ms/created_at 即耗时与时间戳
UPDATE agent_span SET span_id = CONCAT('legacy-', id) WHERE span_id IS NULL;
UPDATE agent_span SET duration_ms = latency_ms, start_time = created_at WHERE start_time IS NULL;
UPDATE agent_span SET end_time = DATE_ADD(created_at, INTERVAL latency_ms * 1000 MICROSECOND) WHERE end_time IS NULL;
UPDATE agent_span s JOIN agent_trace t ON t.trace_id = s.trace_id
SET s.agent_id = t.agent_id,
    s.model_name = IF(s.span_type = 'llm', t.model_name, NULL)
WHERE s.agent_id IS NULL;
-- 非根 Span 挂到本 trace 的 agent 根 Span 下
UPDATE agent_span s JOIN (
    SELECT trace_id, MIN(id) AS root_id FROM agent_span WHERE span_type = 'agent' GROUP BY trace_id
) r ON r.trace_id = s.trace_id AND s.id <> r.root_id
SET s.parent_span_id = CONCAT('legacy-', r.root_id)
WHERE s.parent_span_id IS NULL;

ALTER TABLE agent_span
    ADD KEY idx_span_trace_type_parent (trace_id, span_type, parent_span_id),
    ADD KEY idx_span_tenant_agent_start (tenant_id, agent_id, start_time);

-- 3) agent_metrics_daily（需求 6.13）：仅可加性指标，由定时任务每日生成
CREATE TABLE agent_metrics_daily (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_date    DATE          NOT NULL COMMENT '统计日期',
    tenant_id    BIGINT        NOT NULL,
    agent_id     BIGINT        NOT NULL,
    cost         DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '成本汇总',
    calls        BIGINT        NOT NULL DEFAULT 0 COMMENT '调用量（Trace 数）',
    sessions     BIGINT        NOT NULL DEFAULT 0 COMMENT '会话数（按 session 首日归属）',
    active_users BIGINT        NOT NULL DEFAULT 0 COMMENT '活跃用户数',
    tokens       BIGINT        NOT NULL DEFAULT 0 COMMENT 'Token 总量',
    error_count  BIGINT        NOT NULL DEFAULT 0 COMMENT '错误数',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_metrics_daily (stat_date, tenant_id, agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
