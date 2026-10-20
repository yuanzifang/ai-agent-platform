-- 真正的父子分块：子 chunk 保存所属父块内容与序号，检索命中子块、注入父块上下文
ALTER TABLE knowledge_chunk
    ADD COLUMN parent_index INT NULL COMMENT '父块序号' AFTER chunk_index,
    ADD COLUMN parent_content MEDIUMTEXT NULL COMMENT '父块内容（检索注入用）' AFTER parent_index,
    ADD INDEX idx_chunk_parent (knowledge_base_id, document_id, parent_index);
