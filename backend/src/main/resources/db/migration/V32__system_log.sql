-- 应用运行日志（可观测 Logs 页签；保留期由 system.log.retention-days 控制）
CREATE TABLE IF NOT EXISTS system_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NULL,
    level VARCHAR(16) NOT NULL,
    logger VARCHAR(160) NOT NULL,
    message TEXT NULL,
    exception TEXT NULL,
    trace_id VARCHAR(64) NULL,
    thread VARCHAR(80) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_system_log_tenant_time (tenant_id, created_at),
    KEY idx_system_log_level_time (level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
