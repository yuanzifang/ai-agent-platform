ALTER TABLE knowledge_document
    ADD COLUMN parser_version VARCHAR(32) NULL COMMENT '生成当前解析结果的解析器版本' AFTER parse_fingerprint,
    ADD KEY idx_doc_parser_version (parser_version);
