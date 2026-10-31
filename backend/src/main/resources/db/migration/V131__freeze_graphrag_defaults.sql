-- MVP 范围冻结：知识图谱/GraphRAG 默认关闭。
-- 已有知识库也降级为关闭；需要时由管理员显式开启。
UPDATE sys_config
SET config_value = 'false'
WHERE config_key IN ('knowledge.graph.auto-extract', 'knowledge.graph.retrieval-enabled');

UPDATE knowledge_base SET graph_enabled = 0;

ALTER TABLE knowledge_base
    MODIFY COLUMN graph_enabled TINYINT NOT NULL DEFAULT 0;
