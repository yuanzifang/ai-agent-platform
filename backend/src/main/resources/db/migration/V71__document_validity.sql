-- 知识文档内容有效期：NULL effective_from 表示回退文档 created_at；NULL effective_to 表示长期有效
ALTER TABLE knowledge_document
    ADD COLUMN effective_from DATETIME NULL AFTER created_at,
    ADD COLUMN effective_to DATETIME NULL AFTER effective_from;
