# Agent 容器部署（MVP：本机 Docker）

目标：**发布 → 一键部署 → 容器里跑起来 → 直接调用**。

## 前提

- 本机安装并启动 Docker（Docker Desktop / OrbStack / colima 均可），且命令行能执行：
  ```bash
  docker info
  ```
  macOS 安装示例（本机已按这套装好，开机自启）：
  ```bash
  brew install colima docker
  colima start --vm-type vz --cpu 2 --memory 4
  brew services start colima       # 登录后自动启动，否则重启电脑后 Docker 不可用
  ```
- 国内拉不到 Docker Hub 时，给 colima 配镜像站（本机已配 daocloud）：
  ```bash
  colima ssh -- sh -c 'echo "{\"registry-mirrors\":[\"https://docker.m.daocloud.io\"]}" | sudo tee /etc/docker/daemon.json >/dev/null && sudo systemctl restart docker'
  docker pull docker.m.daocloud.io/library/nginx:alpine
  docker tag docker.m.daocloud.io/library/nginx:alpine nginx:alpine
  ```
- 当前后端需要能访问 Docker CLI（`agent.deploy.docker-bin`，默认 `docker`）

> 本机已用 colima 0.10.3 / Docker 29.5.2 实跑通过：容器 healthy、`/healthz`、`/v1/models`、非流式与 SSE 对话、停止/启动、日志、Tailscale 地址访问全部验证可用。

## 使用

1. Agent 编辑页 → **发布**（容器部署只允许已发布版本）
2. 顶部 **部署到容器** → 立即部署
3. 发布已部署过的 Agent 时，会自动用新版本**重新部署**容器（保证容器与线上版本一致），并提示「已按新版本自动重新部署容器」
4. 弹窗里会出现：
   - 运行状态 / 版本（取最新发布版本号）
   - 对外地址（优先 Tailscale 100.x，其次内网 IP）与 localhost 地址
   - 容器名、镜像、更新时间
   - 可直接复制的 curl 示例
   - 启动 / 停止 / 日志 / **部署历史（可按版本回滚并重新部署）** / 重新部署 / 删除部署
4. 调用示例（需带访问令牌；平台 Key 已注入容器，不需要再传 X-Api-Key）：

```bash
curl -s http://100.82.118.40:19000/v1/chat/completions \
  -H 'Authorization: Bearer <访问令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"model":"agent","messages":[{"role":"user","content":"你好"}]}'
```

令牌在部署弹窗里可直接复制；「重置令牌」会立即作废旧令牌并重建容器。流式（SSE）同样支持：加 `"stream": true`。

## 实现方式

每个 Agent 一个运行时容器，使用**官方 nginx 镜像 + 模板化配置**，不需要额外构建镜像：

```
client → container:80 /v1/chat/completions
       → rewrite → 平台 /api/v1/open/chat/completions
       → 自动带上容器内的 X-Api-Key（该 Agent 的集成 Key）
```

- 部署时自动创建该 Agent 的集成 API Key（`container:aap-agent-<id>`），删除/重新部署时清理
- 配置模板写到 `agent.deploy.workdir`（默认 `~/.aap-agent-runtime/templates`），以只读目录挂载进容器 `/etc/nginx/templates`
- 容器带 `--restart unless-stopped` 与基于 `/healthz` 的 healthcheck

### 表结构

- `agent_deployment`（一个 Agent 一行）：容器名/ID、镜像、端口、地址、状态、API Key 引用、访问令牌（加密）、错误信息、版本、部署人
- `agent_deployment_event`：部署事件流水（DEPLOY/START/STOP/REMOVE、版本、端口、结果、错误、操作人）。部署弹窗「部署历史」直接读它
- 删除 Agent 时会顺带停止/删除容器、清理部署 Key 并记一条 REMOVE 事件（软删除后容器不会残留）

### 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/agent/{id}/deployment` | 状态 + Docker 可用性 |
| POST | `/api/v1/agent/{id}/deployment` | 部署/重新部署 |
| POST | `/api/v1/agent/{id}/deployment/start` | 启动 |
| POST | `/api/v1/agent/{id}/deployment/stop` | 停止 |
| DELETE | `/api/v1/agent/{id}/deployment` | 停止并删除容器 + 清理 Key |
| GET | `/api/v1/agent/{id}/deployment/logs?tail=200` | 容器日志（nginx 访问日志/错误日志） |
| GET | `/api/v1/agent/{id}/deployment/history?limit=50` | 部署历史（部署/启动/停止/删除，含版本、端口、结果、错误） |
| POST | `/api/v1/agent/{id}/versions/{version}/rollback` | 完整回滚 Agent 配置（随后可重新部署） |
| GET | `/api/v1/agent/{id}/deployment/token` | 读取容器访问令牌（agent:edit） |
| POST | `/api/v1/agent/{id}/deployment/token/rotate` | 重置令牌并重建容器 |

## 配置

`application.yml` → `agent.deploy`：

| 配置 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `image` | `AGENT_DEPLOY_IMAGE` | `nginx:alpine` | 运行时镜像 |
| `port-start/port-end` | `AGENT_DEPLOY_PORT_START/END` | 19000-19999 | 宿主端口池（自动挑空端口） |
| `bind` | `AGENT_DEPLOY_BIND` | `0.0.0.0` | 端口绑定地址；改 `127.0.0.1` 则只允许本机 |
| `platform-base-url` | `AGENT_DEPLOY_PLATFORM_BASE` | `http://host.docker.internal:8080` | 容器访问平台的地址 |
| `rate-limit-per-min` | `AGENT_DEPLOY_RATE_LIMIT` | 60 | 每 IP 每分钟请求数（作用于 /v1/ 代理请求） |
| `rate-limit-burst` | `AGENT_DEPLOY_RATE_BURST` | 20 | 突发配额，超出返回 429 |
| `cors-origins` | `AGENT_DEPLOY_CORS_ORIGINS` | `*` | 允许的跨域来源 |
| `public-base` | `AGENT_DEPLOY_PUBLIC_BASE` | 空 | 对外地址前缀，空则自动取 Tailscale/内网 IP |
| `workdir` | `AGENT_DEPLOY_WORKDIR` | `~/.aap-agent-runtime` | 模板目录（需能被 Docker 挂载；macOS 默认只共享 $HOME） |
| `docker-bin` | `AGENT_DEPLOY_DOCKER_BIN` | `docker` | Docker CLI 路径 |

> `host.docker.internal` 在 Docker Desktop / colima 下可用；Linux 上已通过 `--add-host host.docker.internal:host-gateway` 兜底。若后端本身跑在 compose 里，请把 `platform-base-url` 改成平台在容器网络中的地址（如 `http://backend:8080`）。

## 安全说明

- 容器接口需要 `Authorization: Bearer <访问令牌>`；令牌随部署生成、加密存储，可一键重置（重置即重建容器）。`/healthz` 不需要令牌，供容器 healthcheck 使用。
- CORS 默认 `*`，生产建议改成具体前端域名；限流按客户端 IP，超出返回 429（`/v1/models` 与 `/healthz` 走本地直接返回，不计入限流）。
- API Key 与令牌以环境变量形式存在容器里，`docker inspect` 可见；删除部署时会一并删除该 Key。
- 默认绑定 `0.0.0.0` 是为了 Tailscale/手机访问；仍建议只在内网/网关后面暴露该端口。
- 每个 Agent 只保留一个部署记录/容器，重新部署 = 删旧建新（短暂不可用）。重新部署优先复用原端口，最多等待 3 秒。
- 「回滚并部署」= 完整恢复该版本的 Agent 配置（含知识库/工具/技能/子 Agent）+ 重新部署容器，详见 `docs/Agent版本与回滚.md`。
- 发布自动重新部署是「已部署过才触发」；从未部署过的 Agent 发布后不会自动拉起容器，避免意外占用端口。
- 暂未实现：自研运行时镜像、K8s 部署、滚动更新、多副本/负载均衡、容器日志采集到平台可观测、资源配额（CPU/内存限制）。

## 验证

- 后端：`mvn -q -DskipTests compile` / `test-compile`，思考模式单测 7 + 路由单测 2 通过
- 部署链路（假 Docker CLI + 真实后端/数据库）：部署→运行态、端口 19000、环境变量/挂载/健康检查参数、停止/启动/日志、未发布拦截、删除并回收 Key，全部通过
- **真实 Docker（colima + nginx:alpine）**：
  - `docker ps` → `aap-agent-1 Up (healthy) 0.0.0.0:19000->80/tcp`
  - `curl localhost:19000/healthz` → `ok`；`/v1/models` → `agent-1`
  - 容器内访问 `host.docker.internal:8080` 正常（模板与环境变量注入生效）
  - 非流式对话：1.7s 返回真实回答（DeepSeek）；SSE 流式分块正常
  - 停止/启动、`docker logs` 接口、Tailscale 地址 `http://100.82.118.40:19000/healthz` 均正常
  - 临时 Agent：发布 V1 → 部署 19001 → UI 再次发布 V2 → 自动重新部署（历史 DEPLOY/V1、DEPLOY/V2）→ 删除 Agent 后容器与部署记录一并清理
  - 访问令牌：无/错误令牌 401、正确令牌 200；CORS 预检 204 且返回允许头；并发 120 次 chat 中 99 次 429（限流生效）；重置令牌后旧令牌 401、新令牌 200；重启/重新部署不改变令牌（只有重置才变）
- 前端：`vue-tsc`、`vitest`、`vite build` 通过；Playwright 真页面走完「部署 → 运行中 → 日志 → 停止 → 启动 → 删除」，真实 Docker 下同样通过

## 下一步（如需）

1. 换自研 `agent-runtime` 镜像：内置限流、鉴权、CORS、OpenAI 兼容层与健康检查，不再依赖 nginx 模板
2. K8s 实现：`ContainerRuntime` 增加 `KubernetesRuntime`，按租户配额创建 Deployment/Service/Ingress
3. 部署历史与回滚、副本数、资源限制、日志/指标接入可观测
