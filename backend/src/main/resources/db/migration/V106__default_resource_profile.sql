-- 默认检索资源组合；Agent 编辑页无需先手动建 Profile。
INSERT INTO retrieval_resource_profile
    (name, description, search_backend, vector_backend, status)
VALUES ('默认检索资源', 'MySQL + pgvector', 'MYSQL', 'PGVECTOR', 'ENABLED')
ON DUPLICATE KEY UPDATE name = name;
