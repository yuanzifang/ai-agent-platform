-- 文件内容哈希 + embedding 缓存：重复上传可提示；相同 model+文本只嵌入一次
ALTER TABLE knowledge_document
    ADD COLUMN source_sha256 CHAR(64) NULL COMMENT '文件内容 SHA256' AFTER size_bytes,
    ADD KEY idx_doc_sha256 (knowledge_base_id, source_sha256);

CREATE TABLE knowledge_embedding_cache (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    embedding_model VARCHAR(128) NOT NULL,
    content_hash    CHAR(64)     NOT NULL,
    dimensions      INT          NOT NULL,
    embedding       LONGTEXT     NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_embedding_model_hash (embedding_model, content_hash),
    KEY idx_embedding_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
