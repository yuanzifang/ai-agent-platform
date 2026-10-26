-- 新知识库默认 embedding 模型切换为 bge-m3；运行时启动探测未安装会自动回退 nomic
ALTER TABLE knowledge_base
    ALTER COLUMN embedding_model SET DEFAULT 'bge-m3';

UPDATE sys_config
SET config_value = 'bge-m3'
WHERE config_key = 'embedding.default-model';

UPDATE sys_tenant_config
SET config_value = JSON_SET(config_value, '$.modelName', 'bge-m3')
WHERE config_key = 'default_embedding_model'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.modelName')) = 'nomic-embed-text';
