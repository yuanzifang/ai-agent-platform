CREATE TABLE knowledge_vector_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'PENDING / DONE / FAILED',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_vector_outbox_doc (document_id),
    KEY idx_vector_outbox_scan (tenant_id, status, available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MySQL/PgVector 解析同步 outbox';
