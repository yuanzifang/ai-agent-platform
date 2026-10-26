CREATE TABLE knowledge_upgrade_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING / COMPLETED / FAILED',
    total_documents INT NOT NULL DEFAULT 0,
    submitted_documents INT NOT NULL DEFAULT 0,
    succeeded_documents INT NOT NULL DEFAULT 0,
    failed_documents INT NOT NULL DEFAULT 0,
    created_by BIGINT NULL,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_upgrade_job_kb (tenant_id, knowledge_base_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存量文档升级任务';

ALTER TABLE knowledge_document
    ADD COLUMN upgrade_job_id BIGINT NULL COMMENT '存量升级任务 ID' AFTER parse_started_at,
    ADD COLUMN upgrade_status VARCHAR(16) NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED' AFTER upgrade_job_id,
    ADD KEY idx_doc_upgrade (upgrade_job_id, upgrade_status);
