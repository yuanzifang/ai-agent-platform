-- M3 知识库（需求文档 6.7）：知识库/文档/分段/权限

CREATE TABLE knowledge_base (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512) NULL,
    tenant_id        BIGINT       NOT NULL,
    created_by       BIGINT       NULL,
    embedding_model  VARCHAR(64)  NOT NULL DEFAULT 'nomic-embed-text',
    index_version    VARCHAR(32)  NOT NULL DEFAULT 'v1',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kb_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE knowledge_document (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT       NOT NULL,
    file_name         VARCHAR(256) NOT NULL,
    object_name       VARCHAR(512) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    chunk_count       INT          NOT NULL DEFAULT 0,
    error_message     VARCHAR(512) NULL,
    tenant_id         BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_doc_kb (knowledge_base_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE knowledge_chunk (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id       BIGINT       NOT NULL,
    knowledge_base_id BIGINT       NOT NULL,
    chunk_index       INT          NOT NULL DEFAULT 0,
    content           TEXT         NOT NULL,
    image_urls        JSON         NULL,
    tenant_id         BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_chunk_kb_tenant (knowledge_base_id, tenant_id),
    KEY idx_chunk_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE knowledge_permission (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT      NOT NULL,
    subject_type      VARCHAR(16) NOT NULL DEFAULT 'TENANT',
    subject_id        BIGINT      NULL,
    permission        VARCHAR(16) NOT NULL DEFAULT 'VIEW',
    tenant_id         BIGINT      NOT NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_perm_kb (knowledge_base_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
