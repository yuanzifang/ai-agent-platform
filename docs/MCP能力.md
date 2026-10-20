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

## 4. 分页与大小边界

当前实现只解析首屏 `resources/prompts` 列表，不处理 `nextCursor`；大资源没有强制大小上限。生产接入大目录/大文件前应补：

- `nextCursor` 分页
- 资源响应大小上限（例如 1MB）与二进制安全下载
- 资源订阅/变更通知（`resources/list_changed`）

## 5. tools/list_changed 现状

工具列表**没有本地缓存**：每次验证/发现/同步都会重新 `tools/list`，所以工具变化在下次操作时自然生效。当前尚未处理服务端主动 `notifications/tools/list_changed` 自动刷新：

- HTTP/SSE 通知解析未扩展到自动触发同步；
- stdio 通知只记录 debug 日志；
- 不会自动修改已同步到 `tool_registry` 的工具，需管理员手动「同步工具」。

后续如需自动热更新，应增加通知回调 + 工具缓存失效 + 自动同步策略（避免服务端恶意/误发通知直接改工具）。

## 6. 已验证

2026-10-20 Mock MCP（`frontend/e2e/mock_servers.py`）：

- `discover` 返回 tools/resources/resourceTemplates/prompts 均非空；
- `resources/read` 返回 `resource:mock://docs/readme`；
- `prompts/get` 返回 `prompt:hello`；
- Mock 会话复用仍成立（initialize 只发生一次）；
- 完整 E2E **28/28**，后端单测 **48/48**，前端 `vue-tsc`/`vite build`、`helm lint` 通过。

未验证：

- 真实第三方 MCP Server 的 resources/prompts 兼容性（无凭据/网络条件）。
- 分页、二进制资源、资源订阅、`tools/list_changed` 热更新。
- Compose/Helm 未实跑。
