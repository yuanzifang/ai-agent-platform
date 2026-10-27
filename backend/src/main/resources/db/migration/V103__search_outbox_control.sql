-- ES 关闭时不持续写入 outbox；应用启动时按 search.keyword.provider 设置开关。
CREATE TABLE knowledge_search_outbox_control (
    id TINYINT PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO knowledge_search_outbox_control (id, enabled)
VALUES (1, 0)
ON DUPLICATE KEY UPDATE id = id;

DROP TRIGGER IF EXISTS trg_search_chunk_ai;
DROP TRIGGER IF EXISTS trg_search_chunk_au;
DROP TRIGGER IF EXISTS trg_search_chunk_ad;
DROP TRIGGER IF EXISTS trg_search_document_au;

CREATE TRIGGER trg_search_chunk_ai AFTER INSERT ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT NEW.tenant_id, NEW.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
FROM DUAL
WHERE EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

CREATE TRIGGER trg_search_chunk_au AFTER UPDATE ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT NEW.tenant_id, NEW.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
FROM DUAL
WHERE EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

CREATE TRIGGER trg_search_chunk_ad AFTER DELETE ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT OLD.tenant_id, OLD.id, 'DELETE', 'PENDING', 0, NOW(), NOW(), NOW()
FROM DUAL
WHERE EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='DELETE', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

CREATE TRIGGER trg_search_document_au AFTER UPDATE ON knowledge_document
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT kc.tenant_id, kc.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
FROM knowledge_chunk kc
WHERE kc.document_id = NEW.id
  AND EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();
