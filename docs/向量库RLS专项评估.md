# PG 向量库 RLS 专项评估

日期：2026-10-26。结论先行：**不建议启用 PostgreSQL RLS**——当前"每租户独立 Schema"架构下 RLS 无附着点、且会被超级用户连接静默绕过，属于安全剧场；真正有价值的隔离加固是回归测试钉住 schema 边界（本次已补）与生产账号收敛（见文末清单）。

## 一、现状架构（隔离模型）

- **每租户独立 Schema**：`tenant_{id}`（`VectorRepository.schema()`），表内**无 tenant_id 列**——schema 本身就是租户边界，不存在"多租户混居的表"。
- 每维度一张表：`embeddings`（768，历史）/ `embeddings_{n}`（1024 等）。
- 行级访问控制：`metadata->'allowed_users'` / `tenant_visible`（知识库/分片级 ACL），检索 SQL 内过滤。

## 二、注入面与调用方审计（实证）

- **SQL 注入面：无**。schema/table 拼接只来自 `Long tenantId` 与 `int dimensions`（白名单分支）；全部值条件（kb/chunk/document/user）走 `?` 参数化。
- **调用方 7 处**（HybridRetrievalService 的上游）：ChatService（会话上下文）、KnowledgeService 检索测试（`TenantContext` + `requireView` 门）、McpPlatformService（MCP Key 携带租户）、WorkflowService（run 记录租户）、ToolRuntimeService、StepEvaluationService（评测任务租户）——租户来源全部为服务端上下文，无用户可控入参。
- **用户可达入口均有 `KnowledgePermissionService.requireView` 门**。

## 三、为什么不上 RLS（三条硬理由）

1. **无附着点**：RLS 是单表行级策略，前提是多租户行混居一张表并用 `current_setting` 区分。本架构表按租户分 Schema，跨租户行物理不同表——RLS 无表可挂。
2. **会被静默绕过**：应用 PG 连接账号在 CI/本地是超级用户、Compose 是 `aiagent` 建库账号（表 owner）。**表 owner 与超级用户默认 BYPASSRLS**，除非 `FORCE ROW LEVEL SECURITY` + 专用非 owner 角色 + 精细化 GRANT——为一套不生效的机制重构整个账号模型，收益≈0。
3. **Schema 边界强于 RLS**：跨租户访问需显式跨 Schema 引用（代码 review 一眼可见），而 RLS 策略错误（如谓词漏租户条件返回全量）反而更隐蔽。

## 四、本次落地

- **跨租户隔离回归测试** `VectorIsolationIT`（opt-in：`-Dpgvector.it=true`，需本机/CI PG）：
  - 双租户写入互不可见（判别阈值 0.1：泄漏行 score≈1 必现、正交自有行 score=0 不现）；
  - `deleteByKnowledgeBase` 不越界删除。
  - 本机实跑 2/2 通过。
- 差距清单条 7 与 12 步对照 07 行同步。

## 五、遗留观察项与生产化清单

- **观察项（低风险，不改）**：`SET hnsw.iterative_scan` 为会话级设置、连接池复用时残留——该 GUC 无租户维度、幂等重设，良性。
- **生产部署 checklist（非 RLS）**：
  1. 向量库使用**专用非超级用户**（如 Compose 的 `aiagent`），并按 `tenant_*` Schema 模式做 GRANT 收敛（`GRANT USAGE ON SCHEMA tenant_*` + 表级 DML 权限），使运维误操作/其他应用连接也摸不到全量向量；
  2. pgvector/PG 版本升级时回归 `VectorIsolationIT`；
  3. 若未来架构改回单表多租户混居（如做全局检索），**届时**再评估 RLS（该模型下 RLS 才有附着点）。
