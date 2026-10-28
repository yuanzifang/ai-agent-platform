ALTER TABLE knowledge_base
    ADD COLUMN default_top_k INT NOT NULL DEFAULT 5 AFTER vector_backend,
    ADD COLUMN max_top_k INT NOT NULL DEFAULT 20 AFTER default_top_k;
