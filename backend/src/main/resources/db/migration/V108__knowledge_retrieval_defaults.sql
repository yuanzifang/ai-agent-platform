-- 知识库级检索默认参数；Agent 绑定 retrievalConfig 仍可覆盖。
ALTER TABLE knowledge_base
    ADD COLUMN default_retrieval_mode VARCHAR(16) NOT NULL DEFAULT 'hybrid' AFTER max_top_k,
    ADD COLUMN default_score_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.5000 AFTER default_retrieval_mode,
    ADD COLUMN default_keyword_weight DECIMAL(5,4) NOT NULL DEFAULT 0.3500 AFTER default_score_threshold;
