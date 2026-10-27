-- 知识库级选择关键字/向量后端；默认保持现有行为。
ALTER TABLE knowledge_base
    ADD COLUMN search_backend VARCHAR(16) NOT NULL DEFAULT 'MYSQL' AFTER embedding_model,
    ADD COLUMN vector_backend VARCHAR(16) NOT NULL DEFAULT 'PGVECTOR' AFTER search_backend;

-- 只有 ES 配置开启且知识库选择 ES 时才产生 outbox。
DROP TRIGGER IF EXISTS trg_search_chunk_ai;
DROP TRIGGER IF EXISTS trg_search_chunk_au;
DROP TRIGGER IF EXISTS trg_search_chunk_ad;
DROP TRIGGER IF EXISTS trg_search_document_au;

CREATE TRIGGER trg_search_chunk_ai AFTER INSERT ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT NEW.tenant_id, NEW.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
FROM knowledge_base kb
WHERE kb.id = NEW.knowledge_base_id
  AND kb.search_backend = 'ES'
  AND EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

CREATE TRIGGER trg_search_chunk_au AFTER UPDATE ON knowledge_chunk
FOR EACH ROW
INSERT INTO knowledge_search_outbox
    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
SELECT NEW.tenant_id, NEW.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
FROM knowledge_base kb
WHERE kb.id = NEW.knowledge_base_id
  AND kb.search_backend = 'ES'
  AND EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();

-- 删除事件不依赖 KB 行；ES 中可能残留的文档需要删除。
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
JOIN knowledge_base kb ON kb.id = kc.knowledge_base_id
WHERE kc.document_id = NEW.id
  AND kb.search_backend = 'ES'
  AND EXISTS (SELECT 1 FROM knowledge_search_outbox_control WHERE id = 1 AND enabled = 1)
ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING', attempts=0,
    available_at=NOW(), updated_at=NOW();
