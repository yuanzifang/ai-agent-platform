-- PDF 页码溯源：记录子块覆盖的起始/结束页；非 PDF 文档为 NULL
ALTER TABLE knowledge_chunk
    ADD COLUMN page_from INT NULL COMMENT '起始页码（1 基）' AFTER parent_content,
    ADD COLUMN page_to   INT NULL COMMENT '结束页码（1 基）' AFTER page_from,
    ADD KEY idx_chunk_pages (knowledge_base_id, document_id, page_from, page_to);
