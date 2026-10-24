-- 知识图谱完整性修复：
-- 1) entity_type 含 NULL 时唯一键不去重（MySQL 唯一索引对 NULL 不生效），先归一再 NOT NULL
UPDATE knowledge_graph_entity SET entity_type = '' WHERE entity_type IS NULL;
ALTER TABLE knowledge_graph_entity MODIFY entity_type VARCHAR(64) NOT NULL DEFAULT '';

-- 2) 同一对实体的同一关系可重复插入（无防重），先去重再加唯一键
DELETE r1 FROM knowledge_graph_relation r1
JOIN knowledge_graph_relation r2
  ON r1.tenant_id = r2.tenant_id AND r1.kb_id = r2.kb_id
 AND r1.from_id = r2.from_id AND r1.to_id = r2.to_id AND r1.relation = r2.relation
 AND r1.id > r2.id;
ALTER TABLE knowledge_graph_relation
    ADD UNIQUE KEY uk_kb_relation (tenant_id, kb_id, from_id, to_id, relation);
