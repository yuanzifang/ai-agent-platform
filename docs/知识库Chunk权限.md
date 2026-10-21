# 知识库 Chunk 级权限下推

## 1. 目标

知识库原有库级可见性（PRIVATE/TENANT/PARTIAL）在检索入口校验；本批次把 ACL 写入 chunk，并在向量/关键字检索 SQL 内下推，避免先取回再在应用层过滤。

## 2. 数据

V53 `knowledge_chunk`：

- `allowed_users` JSON：显式允许的 userId（字符串数组，包含创建者）
- `tenant_visible`：1=租户可见（TENANT），0=仅 allowed_users

PgVector metadata 同步写：

```json
{
  "knowledge_base_id": 1,
  "document_id": 2,
  "chunk_id": 1,
  "allowed_users": ["2", "4"],
  "tenant_visible": false
}
```

旧向量/分段没有 ACL 时按知识库级权限兜底，避免升级后历史内容不可检索。

## 3. 检索过滤

向量 SQL：

```sql
AND (metadata->'allowed_users' IS NULL
     OR (? = TRUE AND metadata->>'tenant_visible' = 'true')
     OR EXISTS (
       SELECT 1 FROM jsonb_array_elements_text(
         COALESCE(metadata->'allowed_users', '[]'::jsonb)) ua
       WHERE ua = ?))
```

关键字/全文 SQL 同样追加：

```sql
AND (allowed_users IS NULL
     OR (tenant_visible = 1 AND #{tenantWide} = TRUE)
     OR JSON_CONTAINS(allowed_users, JSON_QUOTE(#{userId})))
```

`tenantWide` 由 `KnowledgePermissionService.tenantWide(viewer)` 计算，RESTRICTED 成员为 false。

## 4. 权限变更同步

`PUT /knowledge-base/{id}/permissions` 在更新 `knowledge_permission` 后：

- 同步更新该知识库所有维度的 PgVector metadata；
- 同步更新 `knowledge_chunk.allowed_users/tenant_visible`；
- 新文档入库时直接写入 ACL。

## 5. 已验证

2026-10-21 使用真实 MySQL/PgVector：

- KB 1 从 TENANT 改为 PARTIAL（成员 user 4）后：
  - PgVector metadata：`allowed_users=["2","4"]`，`tenant_visible=false`；
  - `knowledge_chunk` 两行同步；
- 恢复 TENANT 后：`allowed_users=["2"]`，`tenant_visible=true`；
- 直接执行过滤 SQL：user 2 命中 1 条向量/2 条分段；user 999 命中 0；
- 后端单测 51/51。

## 6. 边界

- 当前 ACL 仍由知识库级权限派生，没有 chunk 级独立授权 UI。
- 权限变更同步更新向量 metadata 是同步操作；超大知识库需要异步重建/批量更新。
- 旧数据缺少 ACL 时走“知识库级已校验”的兜底，不等于历史 chunk 已有精细 ACL。
- 向量/关键字 SQL 的 ACL 逻辑通过直接 SQL 验证；未单独做完整文档入库→检索 E2E。
- 向量维度表与 MySQL 分段表若更新部分失败，没有跨库补偿事务；生产需要重试/对账。
