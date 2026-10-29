-- GraphRAG 实体级图嵌入；向量仅用于实体语义检索，不替代关系边。
ALTER TABLE knowledge_graph_entity
    ADD COLUMN embedding LONGTEXT NULL AFTER properties_json,
    ADD COLUMN embedding_model VARCHAR(64) NULL AFTER embedding,
    ADD COLUMN dimensions INT NULL AFTER embedding_model;
