# Agent 运行时镜像（agent-gateway）

自研的单 Agent 运行时：把「一个已发布 Agent」暴露成独立的 OpenAI 兼容接口，镜像内置鉴权、限流、日配额、CORS 与用量统计，不再依赖 nginx 模板挂载。

代码：`runtime/agent-gateway/`（零依赖 Node 22 ESM）

## 构建

```bash
cd runtime/agent-gateway
docker build -t aap/agent-gateway:1 .
```

- 基础镜像 `node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914`（digest 固定，避免 tag 漂移；升级需重新校验并更新 digest）
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
| `API_KEY` / `API_KEY_FILE` | 必填 | 该 Agent 的集成 API Key；`API_KEY_FILE` 优先，平台默认写只读文件 |
| `AGENT_SLUG` | `agent` | `/v1/models` 返回的模型 ID |
| `RUNTIME_TOKEN` / `RUNTIME_TOKEN_FILE` | 空 | 主访问令牌；`RUNTIME_TOKEN_FILE` 优先，平台部署时写只读文件 |
| `PORT` | `8080` | 监听端口 |
| `CORS_ORIGIN` | 空 | 允许的跨域来源；空=不返回 CORS 头，必须显式配置 |
| `RATE_LIMIT_PER_MIN` | `60` | 每 Key 每分钟令牌回填速率，0=不限 |
| `RATE_BURST` | `20` | 突发额度（令牌桶容量 = burst + 1） |
| `CALLER_QUOTA_DAILY` | `0` | 主令牌日调用配额，0=不限 |
| `CALLER_KEYS_JSON` | 空 | 平台注入的调用方 Key：`[{"hash","quota","name"}]`（只含 SHA-256 哈希） |
| `CALLER_KEYS` | 空 | 兼容旧格式：`key1:日配额,key2:0`（明文，不推荐） |
| `STATS_TOKEN` | 等于 `RUNTIME_TOKEN` | `/stats` 的访问令牌 |
| `REDIS_URL` | 空 | `redis://[:password@]host:port/db`；配置后限流/配额/用量改为 Redis 共享，支持多副本 |

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/healthz` | 无 | 健康检查，返回 `ok` |
| GET | `/v1/models` | Bearer | OpenAI 兼容模型列表（`AGENT_SLUG`） |
| POST | `/v1/chat/completions` | Bearer | 转发平台开放 API，支持 `stream: true`（SSE） |
| GET | `/stats` | Bearer（STATS_TOKEN） | 当日按 Key 的请求数/流式数 |
| OPTIONS | `*` | 无 | CORS 预检（204） |

错误统一为 OpenAI 风格：`{"error":{"message":"...","type":"..."}}`。

## Redis 共享（多副本）

配置 `REDIS_URL` 后，以下状态进入 Redis，多个容器副本共享：

- **限流**：Lua 原子令牌桶（`gw:tb:<callerId>`），容量/回填速率与单机实现一致
- **日配额**：`gw:q:<callerId>:<day>`，`INCR` + 过期（2 天），超配额回退计数
- **用量**：`gw:stats:<day>` / `gw:streams:<day>` 哈希计数，保留 7 天

Redis 不可用时自动回退内存统计（并打印一次告警），单副本仍可工作；多副本下回退会导致计数不共享。

## 配额与限流

- **限流**：按调用方 Key 的令牌桶，`RATE_LIMIT_PER_MIN` 回填、`RATE_BURST` 突发；超出返回 429 + `Retry-After`
- **日配额**：`CALLER_KEYS` 中每个 Key 可设置独立日配额，主令牌用 `CALLER_QUOTA_DAILY`；按 UTC 日期重置；超出返回 429 `quota_exceeded`
- **统计**：内存记录当日请求数与流式请求数，`/stats` 可查
- 未配置 `REDIS_URL` 时状态在容器重启后清零（单实例内存实现）；配置后由 Redis 共享

## 平台侧接入

部署服务（`AgentDeploymentService`）在 gateway 模式自动注入：

```text
PLATFORM_BASE / API_KEY / AGENT_SLUG / RUNTIME_TOKEN / PORT
RATE_LIMIT_PER_MIN / RATE_BURST / CORS_ORIGIN / CALLER_QUOTA_DAILY
（可选 CALLER_KEYS）
```

不挂载任何宿主机目录，`docker inspect` 只看到环境变量。

调用方 Key 现在可在部署弹窗的「调用方 Key」面板管理（按 Key 日配额、今日调用、启停/删除），平台注入 `CALLER_KEYS_JSON`（哈希），容器内不保存明文；变更会自动重建容器。

## 验证结果（真实 Docker）

- Redis 多副本：两个容器共享同一 Redis，Key 日配额 3 → 跨副本第 4 次 429；令牌桶 6 次后 A/B 均 429；`/stats` 返回 `store:"redis"` 且计数共享

- 镜像构建成功，容器 `healthy`，无挂载
- `/healthz` 200；无令牌 401；正确令牌 200
- 非流式对话、SSE 流式均正常（DeepSeek + 知识库）
- CORS 预检 204，返回允许头
- 限流：突发 5 次 200 后连续 429（burst=5, 60/min）
- 配额：`CALLER_KEYS=keyA:2` 第 3 次请求 429 `quota_exceeded`
- `/stats` 返回按 Key 的当日计数

## 自动化验证（2026-10-26）

- `cd runtime/agent-gateway && npm test`：8 个 `node:test` 用例，覆盖健康检查/CORS、Bearer 与调用方 Key 鉴权、日配额、分钟级限流、模型列表、JSON/SSE 透传、上游 `X-Api-Key`/`X-Request-Id`、2MB 请求体限制、文件令牌
- CI `gateway` job：`docker build` 运行时镜像 + 启动 Redis service + 容器冒烟，验证 401/200、配额 429、`/stats` 返回 `store:"redis"` 与请求计数、CORS 预检
- 本地已验证：镜像构建成功，Redis 容器冒烟 `store:"redis"`、首请求 200、超日配额 429

## 边界与下一步

- 未配置 Redis 时为单实例内存统计（容器重启清零）；配置 Redis 后支持多副本共享，仍有单点 Redis 依赖
- 目前配额维度是「Key/天」，未做 token 级配额
- 资源限制（CPU/内存）、日志采集到平台可观测、K8s 侧 Deployment/Service 仍是后续项
