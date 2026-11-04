-- 统一 Trace Context：trace_id 是业务链路 ID，request_id 单独保留入口请求标识。
ALTER TABLE agent_trace
    ADD COLUMN request_id VARCHAR(64) NULL AFTER trace_id;

CREATE INDEX idx_trace_request ON agent_trace (request_id);
