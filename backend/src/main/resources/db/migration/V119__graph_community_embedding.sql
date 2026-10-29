-- GraphRAG 社区摘要向量（社区级嵌入，非实体级图嵌入）。
ALTER TABLE knowledge_graph_community
    ADD COLUMN embedding LONGTEXT NULL AFTER summary,
    ADD COLUMN embedding_model VARCHAR(64) NULL AFTER embedding,
    ADD COLUMN dimensions INT NULL AFTER embedding_model;
