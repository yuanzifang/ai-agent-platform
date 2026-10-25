-- 图表数据人工复核状态：仅图表分段使用；NULL/PENDING 表示待核对，REVIEWED 表示已核对
ALTER TABLE knowledge_chunk
    ADD COLUMN review_status VARCHAR(20) NULL AFTER tenant_visible,
    ADD COLUMN reviewed_at DATETIME NULL AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL AFTER reviewed_at,
    ADD KEY idx_chunk_review (knowledge_base_id, review_status);
