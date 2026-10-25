-- 知识图谱按知识库开关：历史知识库默认开启，保持原有行为；新建知识库由创建时选择。
ALTER TABLE knowledge_base
    ADD COLUMN graph_enabled TINYINT NOT NULL DEFAULT 1 AFTER parse_config;
