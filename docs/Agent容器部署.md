# Agent 容器部署（MVP：本机 Docker）

> 2026-10-31 运行时单栈：`agent.deploy.runtime` 已移除，每 Agent 固定部署一个 `nginx:alpine` 入口容器。nginx 只做入口、CORS、`/healthz` 和 `/v1` 反向代理；Bearer 部署令牌、调用方 Key、分钟限流、日配额、用量统计全部由 Spring 平台网关 `/api/v1/gateway/agent-runtime/{agentId}/v1/*` 执行。

目标：**发布 → 一键部署 → 容器里跑起来 → 直接调用**。

> 外部 Docker（UTM/Docker Desktop）必须把 `AGENT_DEPLOY_WORKDIR` 配成宿主共享目录；nginx 模板需要从后端写入该目录，再由 Docker 以只读方式挂载。

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

> 本机已用 colima 0.10.3 / Docker 29.5.2 实跑通过：容器 healthy、`/healthz`、`/v1/models`、非流式与 SSE 对话、停止/启动、日志、Tailscale 地址访问、令牌/配额/限流/统计全部验证可用。

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
4. 调用示例（需带访问令牌；平台集成 Key 保留在平台侧，不需要再传 X-Api-Key）：

```bash
curl -s http://100.82.118.40:19000/v1/chat/completions \
  -H 'Authorization: Bearer <访问令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"model":"agent","messages":[{"role":"user","content":"你好"}]}'
```

令牌在部署弹窗里可直接复制；「重置令牌」会立即作废旧令牌并重建容器。流式（SSE）同样支持：加 `"stream": true`。

## 实现方式

默认使用**平台网关运行时**：每个 Agent 至少一个 `nginx:alpine` 容器，容器内只有模板和入口；多副本时额外一个 nginx LB 容器：

```
client → container:80 /v1/chat/completions
       → nginx 透明反代（保留 Authorization / X-Gray-Tag）
       → 平台 /api/v1/gateway/agent-runtime/{agentId}/v1/chat/completions
       → 平台校验部署令牌/调用方 Key、限流、日配额、用量
       → 平台 /api/v1/open 内部链路（对话/审核/记忆/RAG/工具/计费/Trace）
       → 透传响应（含 SSE）
```

- 无需额外运行时镜像，使用 `nginx:alpine` 入口容器
- 部署时仍会创建该 Agent 的内部集成 API Key（`container:aap-agent-<id>`），但**只留在平台数据库**，不注入 nginx、不写入容器环境变量
- 运行时令牌（部署令牌）加密保存在 `agent_deployment`；平台网关在每次请求时校验，nginx 不持有明文
- 调用方 Key 从 `agent_runtime_key` 表实时读取；新增/停用/删除不需要重建容器
- 容器带 `--restart unless-stopped` 与基于 `/healthz` 的 healthcheck（按容器端口自动生成）

### 表结构

- `agent_deployment`（一个 Agent 一行）：容器名/ID、镜像、端口、地址、状态、API Key 引用、访问令牌（加密）、错误信息、版本、部署人；多副本另有 `replicas_json` 与 LB 端口/容器/地址字段
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
| GET | `/api/v1/agent/{id}/deployment/logs?tail=200&all=true` | 容器日志；`all=true` 聚合所有副本与 LB，默认只取第一个运行副本 |
| GET | `/api/v1/agent/{id}/deployment/history?limit=50` | 部署历史（部署/启动/停止/删除，含版本、端口、结果、错误） |
| POST | `/api/v1/agent/{id}/versions/{version}/rollback` | 完整回滚 Agent 配置（随后可重新部署） |
| GET | `/api/v1/agent/{id}/deployment/token` | 读取容器访问令牌（agent:edit） |
| POST | `/api/v1/agent/{id}/deployment/token/rotate` | 重置令牌并重建容器 |

## 配置

`application.yml` → `agent.deploy`：

| 配置 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `image` | `AGENT_DEPLOY_IMAGE` | `nginx:alpine` | 平台网关运行时入口镜像 |
| `caller-quota-daily` | `AGENT_DEPLOY_CALLER_QUOTA_DAILY` | 0 | 主令牌日配额（0=不限，平台网关执行） |
| `replicas` | `AGENT_DEPLOY_REPLICAS` | 1 | 默认副本数（1-5）；部署时可单独指定 |
| `port-start/port-end` | `AGENT_DEPLOY_PORT_START/END` | 19000-19999 | 宿主端口池（自动挑空端口） |
| `bind` | `AGENT_DEPLOY_BIND` | `127.0.0.1` | 端口绑定地址；默认仅本机 |
| `cpu-limit` | `AGENT_DEPLOY_CPU_LIMIT` | `1` | 每个运行时/LB 容器的 CPU 上限（Docker `--cpus`） |
| `memory-limit` | `AGENT_DEPLOY_MEMORY_LIMIT` | `512m` | 每个运行时/LB 容器的内存上限 |
| `pids-limit` | `AGENT_DEPLOY_PIDS_LIMIT` | `256` | 每个容器的 PID 上限，防止进程失控 |
| `log-max-size` | `AGENT_DEPLOY_LOG_MAX_SIZE` | `10m` | Docker json-file 单文件日志上限 |
| `log-max-file` | `AGENT_DEPLOY_LOG_MAX_FILE` | `3` | Docker 日志保留文件数 |
| `platform-base-url` | `AGENT_DEPLOY_PLATFORM_BASE` | `http://host.docker.internal:8080` | nginx 容器访问平台网关的地址 |
| `rate-limit-per-min` | `AGENT_DEPLOY_RATE_LIMIT` | 60 | 平台网关每调用方每分钟限流（nginx 不做业务限流） |
| `rate-limit-burst` | `AGENT_DEPLOY_RATE_BURST` | 20 | 平台网关令牌桶突发额度 |
| `cors-origins` | `AGENT_DEPLOY_CORS_ORIGINS` | 空 | 允许的跨域来源，需显式配置 |
| `public-base` | `AGENT_DEPLOY_PUBLIC_BASE` | 空 | 对外地址前缀，空则自动取 Tailscale/内网 IP |
| `workdir` | `AGENT_DEPLOY_WORKDIR` | `~/.aap-agent-runtime` | 模板目录；必须位于 Docker 守护进程可见的共享路径 |
| `docker-bin` | `AGENT_DEPLOY_DOCKER_BIN` | `docker` | Docker CLI 路径 |

> `host.docker.internal` 在 Docker Desktop / colima 下可用；Linux 上已通过 `--add-host host.docker.internal:host-gateway` 兜底。若后端本身跑在 compose 里，请把 `platform-base-url` 改成平台在容器网络中的地址（如 `http://backend:8080`）。

## 多副本

部署弹窗可设置「副本数」（1-5），每个副本一个容器、独立端口：

- 副本明细在弹窗内展示（容器名/端口/状态/地址），`agent_deployment` 主行保存第一个副本，明细存 `replicas_json`
- 启停/删除/日志/统计会作用于全部副本
- **平台网关运行时**的限流、日配额与用量统一在 Spring/Redis，跨副本天然共享
- 多副本自动创建一个 nginx LB 容器（`aap-agent-<id>-lb`，迁移 V100），对外暴露稳定「统一入口」，按 `least_conn` 轮询副本；副本故障时自动转发到健康副本
- 统一入口地址写入 `lb_endpoint`/`lb_local_endpoint`，部署弹窗的对外地址和 curl 示例优先使用 LB；副本表仍展示各自端口

## 调用方 Key 与日配额

部署弹窗的「调用方 Key」面板直接管理平台侧的 `agent_runtime_key`：

- **新建 Key**：名称 + 日配额（0=不限），明文只显示一次，可复制给调用方
- 列表显示：名称、前缀、日配额、**今日调用**、状态；支持编辑、停用/启用、删除
- Key 变更**不需要重建容器**：平台网关每次请求实时读取数据库
- 调用方式：`Authorization: Bearer sk-run-...`（与部署主令牌等价，独立配额）
- 容器内不再保存调用方 Key 哈希或明文；`nginx` 只透传 Authorization
- 「今日调用」来自平台网关 `/stats`，按 UTC 日重置；主令牌用量同时显示

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/agent/{id}/runtime-keys` | Key 列表（不含明文） |
| POST | `/api/v1/agent/{id}/runtime-keys` | 新建（仅本次返回明文） |
| PUT | `/api/v1/agent/{id}/runtime-keys/{keyId}` | 改名/配额/启停 |
| DELETE | `/api/v1/agent/{id}/runtime-keys/{keyId}` | 删除 |
| GET | `/api/v1/agent/{id}/deployment/stats` | 平台网关按 Key 当日用量 |

> 平台网关说明：限流/配额/统计优先使用平台 Redis；Redis 不可用时回退单实例内存。

## 安全说明

- 容器接口需要 `Authorization: Bearer <访问令牌>`；令牌随部署生成、加密存储，可一键重置。nginx 容器**不持有**令牌明文；`/healthz` 不需要令牌，供容器 healthcheck 使用。
- 平台网关支持部署主令牌 + 多调用方 Key（数据库管理，各自日配额），限流/配额/用量在 Spring 侧执行；超出返回 429。
- 默认 nginx 容器不注入集成 API Key，也不挂载 Secret 文件；`docker inspect` 看不到平台 Key。
- CORS 默认空，生产需显式配置允许来源。
- 默认绑定 `127.0.0.1`；需 Tailscale/手机访问时显式改 `AGENT_DEPLOY_BIND=0.0.0.0` 并放在网关/内网后。
- 运行时与 LB 容器默认限制 CPU=1、内存=512m、PID=256，并带 `no-new-privileges`；日志使用 Docker json-file 轮转，默认单文件 10m、保留 3 个；可由 `agent.deploy.*` 调整。
- 每个 Agent 只保留一个部署记录（1–5 个运行时副本 + 可选 LB 容器），重新部署 = 删旧建新（短暂不可用）。重新部署优先复用原端口，最多等待 3 秒。
- 「回滚并部署」= 完整恢复该版本的 Agent 配置（含知识库/工具/技能/子 Agent）+ 重新部署容器，详见 `docs/Agent版本与回滚.md`。
- 发布自动重新部署是「已部署过才触发」；从未部署过的 Agent 发布后不会自动拉起容器，避免意外占用端口。
- 暂未实现：K8s 部署、滚动更新、集中式日志存储（Loki 等）；平台已支持按部署聚合副本/LB 日志、资源限制与动态 Prometheus 指标。

## 指标

后端 `/actuator/prometheus` 暴露每个 Agent 部署的状态指标（标签 `agent_id`、`tenant_id`）：

| 指标 | 含义 |
| --- | --- |
| `agent_deployment_running` | 部署整体是否 RUNNING（1/0） |
| `agent_deployment_replicas` | 副本数 |
| `agent_deployment_ready_replicas` | 状态为 RUNNING 的副本数 |
| `agent_deployment_lb_running` | LB 容器是否 RUNNING（1/0） |
| `agent_deployment_events_total` | 部署事件计数（action/status 标签） |

指标随部署、启停、删除和状态刷新更新；删除部署时移除对应时间序列。

## 验证

- 后端全量单测：560/560
- `AgentRuntimeGatewayServiceTest`：部署令牌、调用方 Key、非法令牌、停止部署、限流、日配额、内存统计
- `AgentDeploymentServiceLifecycleTest`：nginx 模板不持有 Key/令牌、平台统计分支、启停/清理/资源限制
- `DockerCliRuntimeTest`：Docker 命令构造、失败处理、inspect JSON；CI 用 `alert-relay:ci` 跑真实 Docker create→start→inspect→logs→stop→remove
- `ArchitectureTest`：gateway 薄层、deploy 依赖边界
- 静态门禁：`scripts/check-mvp-freeze.py`
- 待补：真实 Docker + nginx 容器 + 平台网关全链路回归；外部集群镜像重建

## 下一步（如需）

1. K8s 实现：`ContainerRuntime` 增加 `KubernetesRuntime`，按租户配额创建 Deployment/Service/Ingress
2. 平台网关运行时：继续补真实 Docker/nginx 全链路回归与 token 级配额
3. 容器日志集中存储（Loki）
