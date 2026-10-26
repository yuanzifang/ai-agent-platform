-- 存量数据回填：把旧 chunk.parent_content 收敛到 knowledge_parent；之后子块只保留 parent_index
INSERT INTO knowledge_parent
    (document_id, knowledge_base_id, parent_index, content, tenant_id, created_at)
SELECT document_id,
       knowledge_base_id,
       parent_index,
       MAX(parent_content),
       tenant_id,
       MIN(created_at)
FROM knowledge_chunk
WHERE parent_index IS NOT NULL
  AND parent_content IS NOT NULL
  AND parent_content <> ''
GROUP BY document_id, knowledge_base_id, parent_index, tenant_id
ON DUPLICATE KEY UPDATE content = VALUES(content);

UPDATE knowledge_chunk kc
JOIN knowledge_parent kp
  ON kp.document_id = kc.document_id
 AND kp.parent_index = kc.parent_index
SET kc.parent_content = NULL
WHERE kc.parent_content IS NOT NULL;
