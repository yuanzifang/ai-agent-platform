# MCP Resources / Prompts 能力

在原有 `tools/list`、`tools/call` 基础上，MCP 客户端增加 resources 与 prompts 的发现/读取能力，并在 MCP Server 页面提供「能力」抽屉。

## 1. 支持的方法

| 能力 | MCP 方法 | 后端接口 |
|---|---|---|
| 工具发现 | `tools/list` | `GET /api/v1/mcp-servers/{id}/discover` |
| 资源列表 | `resources/list` | 同上 |
| 资源模板 | `resources/templates/list` | 同上 |
| 读取资源 | `resources/read` | `GET /api/v1/mcp-servers/{id}/resources/read?uri=...` |
| 提示词列表 | `prompts/list` | 同上 |
| 渲染提示词 | `prompts/get` | `POST /api/v1/mcp-servers/{id}/prompts/get` |

`discover` 返回结构：

```json
{
  "tools": [],
  "total": 0,
  "resources": [],
  "resourceTemplates": [],
  "prompts": [],
  "warnings": []
}
```

resources/prompts 是可选能力：老 Server 返回 `method not found` 时降级为空数组并写入 warnings，不影响工具发现/同步。

## 2. 接口示例

```bash
# 读取资源
curl -G http://127.0.0.1:8080/api/v1/mcp-servers/1/resources/read \
  --data-urlencode 'uri=mock://docs/readme' \
  -H "Authorization: Bearer $TOKEN"

# 渲染提示词
curl -X POST http://127.0.0.1:8080/api/v1/mcp-servers/1/prompts/get \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"summarize","arguments":{"text":"hello"}}'
```

- 读取资源返回 `contents[{uri,mimeType,text,blob}]`；`blob` 为 MCP 原样 base64，不自动落盘。
- 渲染提示词返回 `messages` JSON；后端不执行 prompt，也不把它写入 Agent 配置。
- 所有接口要求 `tool:manage`；资源内容视为**不可信外部数据**，页面用纯文本展示，不能作为系统指令执行。

## 3. UI

MCP Server 列表新增「能力」按钮（需 `tool:manage`）：

- 工具 / 资源 / 提示词三个 Tab
- 资源可点击「读取」，在只读弹窗查看文本内容
- 资源模板与 warnings 可见
- 提示词目前展示名称/描述，参数渲染由调用方使用后端接口

## 4. 工具差异预览

`GET /api/v1/mcp-servers/{id}/tools/diff`（需 `tool:manage`）对比服务端发现与已同步工具：

```json
{
  "tools": [{"name": "echo", "change": "UNCHANGED"}],
  "added": [],
  "changed": [],
  "removed": [{"name": "old_tool", "agentRefs": 0, "skillRefs": 0, "referenced": false}],
  "counts": {"total": 1, "added": 0, "changed": 0, "unchanged": 1, "removed": 1},
  "toolsChangedAt": null,
  "note": "removed 仅为预览，不会自动删除；被 Agent/技能引用的工具请先解绑"
}
```

- 同步弹窗展示「新增/变更/未变」标签和差异计数；
- 已选中工具点击「同步选中」即确认写入新增/变更；
- 服务端已移除的工具**只提示、不自动删除**，并显示 Agent/技能引用数；
- 删除工具仍需显式操作，避免自动影响 Agent。

## 5. 分页与大小边界

已实现的保护：

- `tools/list`、`resources/list`、`resources/templates/list`、`prompts/list` 均处理 `nextCursor`；
- 最多 20 页 / 每类 2000 条，重复 cursor 直接拒绝；
- `resources/read` 与 `prompts/get` 单次内容默认上限 **1MB**，通过 `mcp.resource.max-bytes` 配置；
- 超限/重复 cursor/分页过多返回业务错误，不静默截断。

仍未实现：

- 二进制资源的安全下载/落盘（当前直接返回 MCP `blob` base64）
- 资源订阅/变更通知（`resources/list_changed`）
- 流式/超大资源分段

## 6. tools/list_changed 检测

已实现“检测 + 提示 + 手动同步”的安全闭环：

- stdio 读取线程识别 `notifications/tools/list_changed`；
- SSE 事件流识别同名通知；
- HTTP Streamable 的响应体扫描该通知；
- 命中后写入 `mcp_server.tools_changed_at`（V51 持久化），`GET /mcp-servers/{id}` / `discover` 返回该时间；
- MCP Server 页面显示「工具待同步」，能力抽屉/同步弹窗提示；
- 管理员重新「同步工具」成功后清除标记。

**不会自动修改已同步到 `tool_registry` 的工具**：服务端主动通知可能是误发/恶意，自动增删工具会影响 Agent。工具列表本身没有本地缓存，每次发现/同步仍会重新 `tools/list`。

限制：

- HTTP 没有建立长期通知订阅流，只能扫描当前响应；
- 未做自动应用差异；必须管理员确认同步。

## 7. 已验证

2026-10-21 Mock MCP（`frontend/e2e/mock_servers.py`）：

- `discover` 返回 tools/resources/resourceTemplates/prompts 均非空；
- `resources/read` 返回 `resource:mock://docs/readme`；
- `prompts/get` 返回 `prompt:hello`；
- Mock 会话复用仍成立（initialize 只发生一次）；
- stdio Mock 主动发送 `notifications/tools/list_changed`，discover 返回 `toolsChangedAt`，同步后清除（V51 持久化）；
- 工具差异：同步后 0 差异；切换到只暴露 `echo2` 的 Mock 后新增 1、移除 1（echo 未引用）；
- 新增 `/mcp-paged` 两页工具测试：`echo-page1` + `nextCursor` → `echo-page2`，共 2 条；
- 完整 E2E **29/29**（API 25 + UI 4），后端单测 **48/48**，前端 `vue-tsc`/`vite build`、`helm lint` 通过。

## 8. 边界

- 真实第三方 MCP Server 的 resources/prompts 兼容性未验收（无凭据/网络条件）。
- 二进制资源安全下载、资源订阅未实现。
- 工具差异已支持预览和手动确认同步；removed 工具不会自动删除。
- HTTP 长期通知订阅未实现。
- Compose/Helm 未实跑。
