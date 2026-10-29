-- GraphRAG 社区摘要持久化；社区按连通分量成员 ID 稳定标识，供后续图嵌入/检索扩展。
CREATE TABLE knowledge_graph_community (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    community_key VARCHAR(128) NOT NULL,
    name VARCHAR(512) NULL,
    summary TEXT NULL,
    entity_ids TEXT NOT NULL,
    entity_count INT NOT NULL DEFAULT 0,
    types VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_graph_community (kb_id, community_key),
    KEY idx_graph_community_kb (tenant_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GraphRAG community summaries';
