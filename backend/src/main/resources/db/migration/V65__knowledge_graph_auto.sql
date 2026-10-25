-- 知识图谱自动生成 / 可视化 / 手工维护增强
-- 1) 实体增加归一化名称、来源、置信度、扩展属性
ALTER TABLE knowledge_graph_entity
    ADD COLUMN normalized_name VARCHAR(128) NULL,
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN confidence DECIMAL(4,3) NOT NULL DEFAULT 1.000,
    ADD COLUMN properties_json TEXT NULL,
    ADD KEY idx_kb_entity_norm (kb_id, normalized_name);

UPDATE knowledge_graph_entity
SET normalized_name = LOWER(REGEXP_REPLACE(TRIM(name), '[[:space:][:punct:]]', ''))
WHERE normalized_name IS NULL OR normalized_name = '';
ALTER TABLE knowledge_graph_entity MODIFY normalized_name VARCHAR(128) NOT NULL;

-- 2) 关系增加证据分段、来源、置信度
ALTER TABLE knowledge_graph_relation
    ADD COLUMN chunk_id BIGINT NULL,
    ADD COLUMN evidence VARCHAR(512) NULL,
    ADD COLUMN confidence DECIMAL(4,3) NOT NULL DEFAULT 1.000,
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD KEY idx_kb_from (kb_id, from_id),
    ADD KEY idx_kb_to (kb_id, to_id);

-- 3) 实体提及：一个实体可来自多个文档/分段，GraphRAG 可拉取全部证据分段
CREATE TABLE knowledge_graph_mention (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    kb_id       BIGINT       NOT NULL,
    entity_id   BIGINT       NOT NULL,
    document_id BIGINT       NULL,
    chunk_id    BIGINT       NULL,
    quote       VARCHAR(512) NULL,
    extractor   VARCHAR(32)  NOT NULL DEFAULT 'AUTO',
    confidence  DECIMAL(4,3) NOT NULL DEFAULT 1.000,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_chunk (entity_id, chunk_id),
    KEY idx_mention_kb_doc (kb_id, document_id),
    KEY idx_mention_entity (entity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 4) 全库构建任务（异步，前端轮询进度）
CREATE TABLE knowledge_graph_job (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    kb_id               BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    total_documents     INT          NOT NULL DEFAULT 0,
    processed_documents INT          NOT NULL DEFAULT 0,
    total_chunks        INT          NOT NULL DEFAULT 0,
    processed_chunks    INT          NOT NULL DEFAULT 0,
    entity_count        INT          NOT NULL DEFAULT 0,
    relation_count      INT          NOT NULL DEFAULT 0,
    message             VARCHAR(512) NULL,
    error               VARCHAR(1024) NULL,
    created_by          BIGINT       NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          DATETIME     NULL,
    finished_at         DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_job_kb (kb_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 5) 图谱配置（可在 系统设置 → 配置 中修改）
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES
    ('knowledge.graph.auto-extract', 'true', '文档解析完成后自动抽取知识图谱'),
    ('knowledge.graph.retrieval-enabled', 'true', '对话/检索是否启用 GraphRAG 图扩展'),
    ('knowledge.graph.extract-batch-size', '3', '图谱抽取每批处理的 chunk 数（1-5）'),
    ('knowledge.graph.max-chunks-per-doc', '80', '单文档自动抽取的最大 chunk 数');
