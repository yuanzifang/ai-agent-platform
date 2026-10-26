ALTER TABLE knowledge_vector_outbox
    ADD COLUMN embedding_model VARCHAR(64) NULL AFTER attempts,
    ADD COLUMN dimensions INT NULL AFTER embedding_model,
    ADD COLUMN payload LONGTEXT NULL AFTER dimensions;
