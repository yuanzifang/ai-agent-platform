CREATE TABLE knowledge_document_image (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    document_id BIGINT       NOT NULL,
    object_name VARCHAR(512) NOT NULL,
    file_name   VARCHAR(256) NULL,
    mime_type   VARCHAR(64)  NULL,
    size_bytes  BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kb_doc_image (document_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
