ALTER TABLE knowledge_document
    ADD COLUMN embedding_model VARCHAR(64) NULL AFTER upgrade_attempts,
    ADD COLUMN embedding_dimensions INT NULL AFTER embedding_model,
    ADD COLUMN vector_verified_at DATETIME NULL AFTER embedding_dimensions,
    ADD KEY idx_doc_vector_audit (status, vector_verified_at);
