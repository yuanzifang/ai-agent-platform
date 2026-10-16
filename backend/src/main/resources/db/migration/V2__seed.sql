-- M1 种子数据（架构 10.8）：权限点、默认角色、平台管理员、平台参数默认值

INSERT INTO sys_permission (perm_key, name) VALUES
('agent:create', '创建 Agent'), ('agent:edit', '编辑 Agent'), ('agent:delete', '删除 Agent'),
('agent:publish', '发布 Agent'), ('kb:create', '创建知识库'), ('kb:edit', '编辑知识库'),
('kb:delete', '删除知识库'), ('model:manage', '模型管理'), ('eval:manage', '评测管理'),
('billing:read', '用量查看'), ('audit:read', '审计查看'), ('tenant:manage', '租户管理'),
('user:manage', '成员管理'), ('prompt:manage', '模板管理'), ('tool:manage', '工具管理'),
('skill:manage', '技能管理');

INSERT INTO sys_role (tenant_id, role_key, name, is_builtin) VALUES
(NULL, 'OWNER', '所有者', 1),
(NULL, 'ADMIN', '管理员', 1),
(NULL, 'EDITOR', '编辑者', 1),
(NULL, 'MEMBER', '普通成员', 1),
(NULL, 'RESTRICTED', '受限成员', 1);

-- 平台管理员：密码占位，启动时由 PLATFORM_ADMIN_PASSWORD 重置（AdminPasswordInitializer）
INSERT INTO sys_user (username, password_hash, display_name, is_platform_admin, is_service_account,
                      tenant_id, status, created_at)
VALUES ('admin', '__RESET__', '平台管理员', 1, 0, NULL, 'ENABLED', NOW());

INSERT INTO sys_config (config_key, config_value, description) VALUES
('trace.sample-rate', '1.0', 'Trace 采样率'),
('eval.sample-rate', '0.1', '在线监控抽样比例'),
('agent.max-iterations', '10', 'Agent 默认最大迭代次数'),
('llm.timeout-ms', '60000', '单次 LLM 调用超时'),
('llm.retry', '2', 'LLM 调用重试次数'),
('publish.gate-threshold', '0.8', '预发布测评门禁阈值'),
('share.default-days', '7', '会话分享链接默认有效期（天）'),
('embedding.default-model', 'nomic-embed-text', '租户默认嵌入模型（本地 Ollama）');
