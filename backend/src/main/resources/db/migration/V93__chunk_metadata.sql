ALTER TABLE knowledge_chunk
    ADD COLUMN document_type VARCHAR(32) NULL COMMENT 'research_paper/regulatory/clinical_trial/general' AFTER parent_index,
    ADD COLUMN section_type VARCHAR(32) NULL COMMENT 'abstract/introduction/methods/results/discussion/conclusion/references/body' AFTER document_type,
    ADD COLUMN chunk_level INT NULL COMMENT '标题层级 1-4' AFTER section_type,
    ADD COLUMN parent_key VARCHAR(64) NULL COMMENT 'documentId:parentIndex' AFTER chunk_level,
    ADD COLUMN domain_keywords JSON NULL COMMENT '预抽取领域关键词' AFTER parent_key,
    ADD COLUMN relevance_score DOUBLE NULL COMMENT '静态相关性评分 0-1' AFTER domain_keywords,
    ADD COLUMN regulatory_category VARCHAR(16) NULL COMMENT 'FDA/EMA/ICH' AFTER relevance_score,
    ADD KEY idx_chunk_meta (knowledge_base_id, document_type, section_type, regulatory_category),
    ADD KEY idx_chunk_relevance (knowledge_base_id, relevance_score);

ALTER TABLE knowledge_parent
    ADD COLUMN document_type VARCHAR(32) NULL AFTER content,
    ADD COLUMN section_type VARCHAR(32) NULL AFTER document_type,
    ADD COLUMN chunk_level INT NULL AFTER section_type,
    ADD COLUMN domain_keywords JSON NULL AFTER chunk_level,
    ADD COLUMN relevance_score DOUBLE NULL AFTER domain_keywords,
    ADD COLUMN regulatory_category VARCHAR(16) NULL AFTER relevance_score;
