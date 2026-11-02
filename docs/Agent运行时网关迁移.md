# Agent 运行时网关迁移：Node → Spring 平台网关

日期：2026-10-31

## 结论

运行时已收敛为单栈：

```text
client
  → 每 Agent 的 nginx 容器（入口/反代/CORS/healthz）
  → Spring 平台网关 /api/v1/gateway/agent-runtime/{agentId}/v1/*
  → 平台内部对话链路 /api/v1/open
```

- 旧 Node `runtime/agent-gateway` 已删除，不再提供 `gateway` 运行时回滚。
- 前端 nginx、每 Agent nginx、多副本 LB nginx 保留。
- Bearer、调用方 Key、限流、日配额、用量统计全部在 Spring 执行。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/gateway/agent-runtime/{agentId}/v1/models` | OpenAI 兼容模型列表 |
| POST | `/api/v1/gateway/agent-runtime/{agentId}/v1/chat/completions` | 对话，支持 SSE |
| GET | `/api/v1/gateway/agent-runtime/{agentId}/stats` | 当日按调用方用量（仅部署令牌） |

鉴权头：

```text
Authorization: Bearer <部署令牌>
Authorization: Bearer sk-run-<调用方 Key>
```

部署令牌来自 `agent_deployment.runtime_token`（AES 加密），调用方 Key 来自 `agent_runtime_key`（只存 SHA-256）。nginx 不接触两者明文，也不注入平台集成 API Key。

## 职责

| 能力 | 平台网关 | nginx |
| --- | --- | --- |
| Bearer / 调用方 Key | ✅ | ❌ |
| 分钟令牌桶限流 | ✅ | ❌ |
| 日配额 | ✅ | ❌ |
| 用量统计 | ✅ Redis / 内存 | ❌ |
| OpenAI JSON / SSE | ✅ 复用 OpenApiController | 只透传 |
| `/healthz` | ❌ | ✅ |
| 多副本 LB | ❌ | ✅ |

Redis key：

```text
agent-runtime:rate:{agentId}:{callerId}
agent-runtime:quota:{agentId}:{callerId}:{yyyyMMdd}
agent-runtime:stats:{agentId}:{yyyyMMdd}
agent-runtime:streams:{agentId}:{yyyyMMdd}
```

Redis 不可用时回退本机内存，单实例可用；多实例下统计/配额不共享。

## 部署

```text
AGENT_DEPLOY_ENABLED=true
AGENT_DEPLOY_RUNTIME=auto        # auto/kubernetes/docker
```

不再有 Node gateway 的 `gateway-image`、`caller-keys`、`redis-url` 配置。

nginx 模板由 `AgentDeploymentService` 生成：

```text
location /v1/ {
    proxy_pass ${PLATFORM_BASE}/api/v1/gateway/agent-runtime/${AGENT_ID}/v1/;
    proxy_set_header Authorization $http_authorization;
    ...
}
```

## 验证

- `AgentRuntimeGatewayServiceTest` 6 个用例：部署令牌、调用方 Key、缺失/非法令牌、停止部署、限流、日配额、内存统计
- `AgentRuntimeGatewayControllerTest` 4 个用例：入口复用、OpenAI 错误结构、stats 仅部署令牌
- `AgentDeploymentServiceLifecycleTest` 11 个用例：nginx 模板不持有 Key/令牌、平台统计分支、启停/清理/资源限制
- `ArchitectureTest` 3 个用例：gateway 薄层、deploy 依赖边界
- 后端全量单测：569/569
- 静态门禁：`scripts/check-mvp-freeze.py` 通过
- 待补：真实 Docker + nginx 容器 + 平台网关全链路实测；外部集群镜像重建

## 未改动

- 外部 k3d/UTM 集群旧 `aap-agent-gateway` 资源需与并行部署任务协调下线；仓库已不再部署，若旧 manifest 被重新 apply 会再次创建
- 前端 nginx
- `aap` release/namespace
