-- ES 关键字索引 outbox。MySQL 是事实源；ES 只做异步索引。
CREATE TABLE knowledge_search_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL DEFAULT 'UPSERT',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_search_outbox_chunk (tenant_id, chunk_id),
    KEY idx_search_outbox_scan (status, available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ES keyword index outbox';

CREATE TRIGGER trg_search_chunk_ai AFTER INSERT ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
VALUES (NEW.tenant_id, NEW.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW())
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

CREATE TRIGGER trg_search_chunk_au AFTER UPDATE ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
VALUES (NEW.tenant_id, NEW.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW())
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

CREATE TRIGGER trg_search_chunk_ad AFTER DELETE ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
VALUES (OLD.tenant_id, OLD.id, 'DELETE', 'PENDING', 0, NOW(), NOW(), NOW())
ON DUPLICATE KEY UPDATE operation='DELETE', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

-- 文档有效期变更需要重新索引该文档的所有分段。
CREATE TRIGGER trg_search_document_au AFTER UPDATE ON knowledge_document
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT kc.tenant_id, kc.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
FROM knowledge_chunk kc
WHERE kc.document_id = NEW.id
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();
