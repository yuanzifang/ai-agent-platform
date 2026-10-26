ALTER TABLE knowledge_document
    ADD COLUMN parse_started_at DATETIME NULL COMMENT '本次解析开始时间' AFTER parse_fingerprint,
    ADD KEY idx_doc_parsing (status, parse_started_at);
