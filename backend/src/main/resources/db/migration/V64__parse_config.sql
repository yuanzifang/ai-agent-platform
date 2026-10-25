-- 解析配置下放（需求评审）：知识库级默认 + 文档级覆盖（上传时选择）
ALTER TABLE knowledge_base ADD COLUMN parse_config JSON NULL;
ALTER TABLE knowledge_document ADD COLUMN parse_config JSON NULL;
