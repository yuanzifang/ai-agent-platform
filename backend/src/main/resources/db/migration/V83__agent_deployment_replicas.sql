-- 部署多副本：主行仍保存第一个副本（兼容旧字段），副本明细放 JSON
ALTER TABLE agent_deployment
    ADD COLUMN replica_count INT NOT NULL DEFAULT 1 AFTER host_port,
    ADD COLUMN replicas_json JSON NULL AFTER replica_count;
