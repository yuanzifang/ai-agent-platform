-- 审计哈希链版本：V1 历史行无法严格重算（并发/时间戳），V2 起串行写入且 createdAt 与哈希一致
ALTER TABLE audit_operation
    ADD COLUMN hash_version TINYINT NOT NULL DEFAULT 1 AFTER prev_hash;
