-- 父块内容独立表：同一父块只存一份，子块只保留 parent_index
CREATE TABLE knowledge_parent (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id       BIGINT       NOT NULL,
    knowledge_base_id BIGINT       NOT NULL,
    parent_index      INT          NOT NULL,
    content           MEDIUMTEXT   NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_parent_doc_index (document_id, parent_index),
    KEY idx_parent_kb_tenant (knowledge_base_id, tenant_id),
    KEY idx_parent_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
