-- 多副本 Agent 运行时统一入口（nginx 负载均衡容器）
ALTER TABLE agent_deployment
    ADD COLUMN lb_container_name VARCHAR(128) NULL AFTER replicas_json,
    ADD COLUMN lb_container_id VARCHAR(128) NULL AFTER lb_container_name,
    ADD COLUMN lb_host_port INT NULL AFTER lb_container_id,
    ADD COLUMN lb_status VARCHAR(32) NULL AFTER lb_host_port,
    ADD COLUMN lb_endpoint VARCHAR(512) NULL AFTER lb_status,
    ADD COLUMN lb_local_endpoint VARCHAR(512) NULL AFTER lb_endpoint;
