ALTER TABLE knowledge_document
    ADD COLUMN parse_fingerprint CHAR(64) NULL COMMENT '解析配置指纹' AFTER source_sha256,
    ADD KEY idx_doc_reuse (tenant_id, source_sha256, parse_fingerprint, status);
