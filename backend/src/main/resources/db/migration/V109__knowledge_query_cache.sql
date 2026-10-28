-- 知识库级 embedding query cache；0 表示关闭缓存。
ALTER TABLE knowledge_base
    ADD COLUMN query_cache_ttl_seconds INT NOT NULL DEFAULT 300 AFTER default_keyword_weight,
    ADD COLUMN query_cache_max_entries INT NOT NULL DEFAULT 2000 AFTER query_cache_ttl_seconds;
