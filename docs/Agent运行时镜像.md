# Agent 运行时镜像（agent-gateway）

自研的单 Agent 运行时：把「一个已发布 Agent」暴露成独立的 OpenAI 兼容接口，镜像内置鉴权、限流、日配额、CORS 与用量统计，不再依赖 nginx 模板挂载。

代码：`runtime/agent-gateway/`（零依赖 Node 22 ESM）

## 构建

```bash
cd runtime/agent-gateway
docker build -t aap/agent-gateway:1 .
```

- 基础镜像 `node:22-alpine`（国内可用 daocloud 镜像站拉取）
- 镜像无 npm 依赖，构建即用
- 自带 `HEALTHCHECK`，部署服务也会按宿主端口再探一次 `/healthz`

平台默认通过 `agent.deploy.runtime=gateway` 使用该镜像；`agent.deploy.runtime=nginx` 可回退旧的 nginx 模板方案。

## 架构

```
client → container:8080 /v1/chat/completions
       → 校验 Bearer（RUNTIME_TOKEN / CALLER_KEYS）
       → 限流 + 日配额
       → 平台 /api/v1/open/chat/completions（带 X-Api-Key）
       → 原样透传响应（含 SSE 流）
```

平台集成 Key 只在容器内使用，调用方拿不到；调用方只持有运行时令牌。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PLATFORM_BASE` | `http://host.docker.internal:8080` | 平台地址（容器内可访问） |
| `API_KEY` | 必填 | 该 Agent 的集成 API Key（平台注入） |
| `AGENT_SLUG` | `agent` | `/v1/models` 返回的模型 ID |
| `RUNTIME_TOKEN` | 空 | 主访问令牌；平台部署时自动生成 |
| `PORT` | `8080` | 监听端口 |
| `CORS_ORIGIN` | `*` | 允许的跨域来源 |
| `RATE_LIMIT_PER_MIN` | `60` | 每 Key 每分钟令牌回填速率，0=不限 |
| `RATE_BURST` | `20` | 突发额度（令牌桶容量 = burst + 1） |
| `CALLER_QUOTA_DAILY` | `0` | 主令牌日调用配额，0=不限 |
| `CALLER_KEYS` | 空 | 额外调用方 Key：`key1:日配额,key2:0`（0=不限） |
| `STATS_TOKEN` | 等于 `RUNTIME_TOKEN` | `/stats` 的访问令牌 |

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/healthz` | 无 | 健康检查，返回 `ok` |
| GET | `/v1/models` | Bearer | OpenAI 兼容模型列表（`AGENT_SLUG`） |
| POST | `/v1/chat/completions` | Bearer | 转发平台开放 API，支持 `stream: true`（SSE） |
| GET | `/stats` | Bearer（STATS_TOKEN） | 当日按 Key 的请求数/流式数 |
| OPTIONS | `*` | 无 | CORS 预检（204） |

错误统一为 OpenAI 风格：`{"error":{"message":"...","type":"..."}}`。

## 配额与限流

- **限流**：按调用方 Key 的令牌桶，`RATE_LIMIT_PER_MIN` 回填、`RATE_BURST` 突发；超出返回 429 + `Retry-After`
- **日配额**：`CALLER_KEYS` 中每个 Key 可设置独立日配额，主令牌用 `CALLER_QUOTA_DAILY`；按 UTC 日期重置；超出返回 429 `quota_exceeded`
- **统计**：内存记录当日请求数与流式请求数，`/stats` 可查
- 以上状态在容器重启后清零（当前为单实例内存实现）

## 平台侧接入

部署服务（`AgentDeploymentService`）在 gateway 模式自动注入：

```text
PLATFORM_BASE / API_KEY / AGENT_SLUG / RUNTIME_TOKEN / PORT
RATE_LIMIT_PER_MIN / RATE_BURST / CORS_ORIGIN / CALLER_QUOTA_DAILY
（可选 CALLER_KEYS）
```

不挂载任何宿主机目录，`docker inspect` 只看到环境变量。

额外调用方 Key 目前通过 `AGENT_DEPLOY_CALLER_KEYS`（平台配置）注入，后续可做成按 Agent 的可视化 Key 管理。

## 验证结果（真实 Docker）

- 镜像构建成功，容器 `healthy`，无挂载
- `/healthz` 200；无令牌 401；正确令牌 200
- 非流式对话、SSE 流式均正常（DeepSeek + 知识库）
- CORS 预检 204，返回允许头
- 限流：突发 5 次 200 后连续 429（burst=5, 60/min）
- 配额：`CALLER_KEYS=keyA:2` 第 3 次请求 429 `quota_exceeded`
- `/stats` 返回按 Key 的当日计数

## 边界与下一步

- 单实例、内存统计：多副本需要外部存储（Redis）汇总配额/用量
- 目前配额维度是「Key/天」，未做 token 级配额
- 资源限制（CPU/内存）、日志采集到平台可观测、K8s 侧 Deployment/Service 仍是后续项
