-- P0 权限体系落地（需求 3.5/6.33）：角色-权限点映射 + Agent 用户级授权
CREATE TABLE sys_role_permission (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id       BIGINT      NOT NULL,
    permission_id BIGINT      NOT NULL,
    UNIQUE KEY uk_role_perm (role_id, permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 角色权限矩阵（需求 3.5：OWNER/ADMIN 全部；EDITOR 创建编辑发布；MEMBER 使用；RESTRICTED 受限只读）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
WHERE r.tenant_id IS NULL AND r.role_key IN ('OWNER', 'ADMIN');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
WHERE r.tenant_id IS NULL AND r.role_key = 'EDITOR'
  AND p.perm_key IN ('agent:create', 'agent:edit', 'agent:publish', 'kb:create', 'kb:edit',
                     'prompt:manage', 'eval:manage');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
WHERE r.tenant_id IS NULL AND r.role_key = 'MEMBER'
  AND p.perm_key IN ('billing:read');

-- RESTRICTED：无任何权限点（只读使用）

-- Agent 用户级授权（需求 3.3/6.41）：visibility=PRIVATE 时仅所有者/被授权用户/管理员可见可用
CREATE TABLE agent_user_grant (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id   BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    tenant_id  BIGINT      NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_user (agent_id, user_id),
    KEY idx_grant_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
