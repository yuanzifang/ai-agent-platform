-- K8s requests：Docker 只使用 limits，K8s runtime 可按此映射 requests/limits。
ALTER TABLE agent_deploy_profile
    ADD COLUMN cpu_request VARCHAR(32) NOT NULL DEFAULT '250m' AFTER cpu_limit,
    ADD COLUMN memory_request VARCHAR(32) NOT NULL DEFAULT '256Mi' AFTER memory_limit;
