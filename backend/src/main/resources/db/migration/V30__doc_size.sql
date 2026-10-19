-- 知识库文档大小（用于存储配额统计）
ALTER TABLE knowledge_document ADD COLUMN size_bytes BIGINT NOT NULL DEFAULT 0 AFTER chunk_count;
