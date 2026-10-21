-- chunk 级 ACL：与 PgVector metadata 同步，供向量/关键字两条检索链路过滤
ALTER TABLE knowledge_chunk
    ADD COLUMN allowed_users JSON NULL AFTER content,
    ADD COLUMN tenant_visible TINYINT NULL AFTER allowed_users;
