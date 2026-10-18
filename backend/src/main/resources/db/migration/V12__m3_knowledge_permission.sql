-- M3 知识库权限补全（需求 3.6.3(5)(6) / 3.9(2)）：
-- visibility: PRIVATE=只有我 / TENANT=所有团队成员（不含受限成员）/ PARTIAL=部分团队成员
ALTER TABLE knowledge_base
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT' AFTER created_by;

-- 授权行按 (知识库, 用户) 唯一，便于整体覆盖时 upsert
ALTER TABLE knowledge_permission
    ADD UNIQUE KEY uk_perm_kb_user (knowledge_base_id, subject_id);
