CREATE TABLE knowledge_graph_entity (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    kb_id       BIGINT       NOT NULL,
    name        VARCHAR(128) NOT NULL,
    entity_type VARCHAR(64)  NULL,
    description VARCHAR(1024) NULL,
    document_id BIGINT       NULL,
    chunk_id    BIGINT       NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_entity (tenant_id, kb_id, name, entity_type),
    KEY idx_kb_entity (kb_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_graph_relation (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    kb_id       BIGINT       NOT NULL,
    from_id     BIGINT       NOT NULL,
    to_id       BIGINT       NOT NULL,
    relation    VARCHAR(64)  NOT NULL,
    weight      DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    document_id BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kb_relation (kb_id, id),
    KEY idx_from (from_id),
    KEY idx_to (to_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
