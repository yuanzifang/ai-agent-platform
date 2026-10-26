-- 部署容器访问令牌（加密存储；调用端用 Authorization: Bearer <token>）
ALTER TABLE agent_deployment
    ADD COLUMN runtime_token VARCHAR(512) NULL COMMENT '容器访问令牌（CryptoService 加密）' AFTER api_key_id;
