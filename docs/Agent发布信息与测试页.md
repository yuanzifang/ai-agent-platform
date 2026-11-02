# Agent 发布信息与外部测试页

日期：2026-11-02

## 发布信息

Agent 编辑页顶部新增 **发布信息** 按钮，打开后展示：

- Agent 发布/部署状态
- 已部署服务的对外地址
- 集群内地址
- 外部测试页入口

数据来源：

```text
GET /api/v1/agent/{id}/deployment
```

已有 Agent 打开编辑页时会自动读取部署信息；未部署时明确提示“尚未部署”。

### 地址说明

- K8s ClusterIP 地址包含 `.svc.cluster.local`，页面会提示“仅集群内访问”。
- 启用 Agent Ingress 后，部署 endpoint 会变成外部域名，例如：

```text
https://agent-1.agents.example.com/v1
```

- 外部地址变更后需要重新部署 Agent，使 `agent_deployment.endpoint` 更新。

## 外部测试页

发布信息中可以点击 **生成测试链接**：

1. 平台创建一个 `WIDGET` 渠道的 API Key
2. 生成公开测试页：

```text
https://<platform>/widget?key=<widget-key>
```

3. 同时生成 iframe 代码：

```html
<iframe src="https://<platform>/widget?key=<widget-key>"
        style="width:100%;height:640px;border:0;border-radius:12px"
        title="Agent test"></iframe>
```

测试者不需要登录平台，直接打开页面即可对话，底层调用：

```text
/api/v1/open/agent/info
/api/v1/open/chat/completions
```

### 安全

- 测试链接包含 API Key，只发给需要验证的人。
- Key 有独立的分钟限流和日配额（测试链接默认 60 次/分钟、日不限）。
- 验证完成后，在 Agent 集成的 API Key 列表中停用或删除该 Key。
- API Key 明文只在创建时返回；发布信息不要保存明文。

## 为什么不复用外部 endpoint

测试页使用平台同源的 `/api/v1/open`：

- 不受 Agent Ingress/ClusterIP、CORS 或证书限制
- 页面只需要能访问平台前端
- API 调用方仍可单独使用发布信息里的外部 endpoint 做接口验证

## 验证

```text
前端 build: 通过
vue-tsc: 通过
i18n key parity: 2968/2968
widget 页面: HTTP 200
WIDGET Key 创建/查询/删除: 通过
/api/v1/open/agent/info via frontend proxy: 200
```
