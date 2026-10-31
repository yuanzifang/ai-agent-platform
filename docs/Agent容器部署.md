# Agent 容器部署（MVP：本机 Docker）

> 2026-10-31 运行时收敛：默认 `agent.deploy.runtime=nginx`。nginx 容器只做入口、CORS/静态健康检查和 `/v1` 反向代理；Bearer 部署令牌、调用方 Key、分钟限流、日配额、用量统计全部由 Spring 平台网关 `/api/v1/gateway/agent-runtime/{agentId}/v1/*` 执行。
>
> 旧 Node `aap/agent-gateway` 运行时保留为 `AGENT_DEPLOY_RUNTIME=gateway` 兼容回滚，不再是默认路径；默认 nginx 容器不再接触集成 API Key 或运行时令牌明文。迁移细节见 `docs/Agent运行时网关迁移.md`。

目标：**发布 → 一键部署 → 容器里跑起来 → 直接调用**。

> 外部 Docker（UTM/Docker Desktop）仍必须把 `AGENT_DEPLOY_WORKDIR` 配成宿主共享目录；nginx 模板需要从后端写入该目录，再由 Docker 以只读方式挂载。旧 Node gateway 的只读 Secret 目录仅在使用 `AGENT_DEPLOY_RUNTIME=gateway` 时需要。

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

默认使用**平台网关运行时**：每个 Agent 至少一个 `nginx:alpine` 容器，容器内只有模板和入口，不再运行 Node 鉴权进程；多副本时额外一个 nginx LB 容器：

```
client → container:80 /v1/chat/completions
       → nginx 透明反代（保留 Authorization / X-Gray-Tag）
       → 平台 /api/v1/gateway/agent-runtime/{agentId}/v1/chat/completions
       → 平台校验部署令牌/调用方 Key、限流、日配额、用量
       → 平台 /api/v1/open 内部链路（对话/审核/记忆/RAG/工具/计费/Trace）
       → 透传响应（含 SSE）
```

- 默认无需构建 Node 镜像；`agent.deploy.runtime=gateway` 时仍可构建 `aap/agent-gateway:1` 作为兼容回滚
- 部署时仍会创建该 Agent 的内部集成 API Key（`container:aap-agent-<id>`），但**只留在平台数据库**，不注入 nginx、不写入容器环境变量
- 运行时令牌（部署令牌）加密保存在 `agent_deployment`；平台网关在每次请求时校验，nginx 不持有明文
- 调用方 Key 从 `agent_runtime_key` 表实时读取；新增/停用/删除不需要重建容器
- 容器带 `--restart unless-stopped` 与基于 `/healthz` 的 healthcheck（按容器端口自动生成）
- 旧 Node 运行时仍保留 `docs/Agent运行时镜像.md` 的兼容说明

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
| `runtime` | `AGENT_DEPLOY_RUNTIME` | `nginx` | `nginx`=平台网关运行时（推荐）；`gateway`=旧 Node 运行时（兼容回滚） |
| `gateway-image` | `AGENT_DEPLOY_GATEWAY_IMAGE` | `aap/agent-gateway:1` | 旧 Node 运行时镜像 |
| `image` | `AGENT_DEPLOY_IMAGE` | `nginx:alpine` | 平台网关运行时入口镜像 |
| `caller-quota-daily` | `AGENT_DEPLOY_CALLER_QUOTA_DAILY` | 0 | 主令牌日配额（0=不限，平台网关执行） |
| `caller-keys` | `AGENT_DEPLOY_CALLER_KEYS` | 空 | 旧 Node 运行时兼容配置；平台网关从 `agent_runtime_key` 读取 |
| `replicas` | `AGENT_DEPLOY_REPLICAS` | 1 | 默认副本数（1-5）；部署时可单独指定 |
| `redis-url` | `AGENT_DEPLOY_REDIS_URL` | `redis://host.docker.internal:6379/0` | 旧 Node 多副本兼容配置；平台网关使用平台自身 Redis |
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
| `workdir` | `AGENT_DEPLOY_WORKDIR` | `~/.aap-agent-runtime` | 模板/旧 gateway Secret 文件目录；必须位于 Docker 守护进程可见的共享路径 |
| `docker-bin` | `AGENT_DEPLOY_DOCKER_BIN` | `docker` | Docker CLI 路径 |

> `host.docker.internal` 在 Docker Desktop / colima 下可用；Linux 上已通过 `--add-host host.docker.internal:host-gateway` 兜底。若后端本身跑在 compose 里，请把 `platform-base-url` 改成平台在容器网络中的地址（如 `http://backend:8080`）。

## 多副本

部署弹窗可设置「副本数」（1-5），每个副本一个容器、独立端口：

- 副本明细在弹窗内展示（容器名/端口/状态/地址），`agent_deployment` 主行保存第一个副本，明细存 `replicas_json`
- 启停/删除/日志/统计会作用于全部副本
- **平台网关运行时**的限流、日配额与用量统一在 Spring/Redis，跨副本天然共享；不再要求旧 Node 的 `agent.deploy.redis-url`
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
- 旧 Node `gateway` 运行时仍兼容 `CALLER_KEYS_JSON` 注入，但仅作为回滚路径

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/agent/{id}/runtime-keys` | Key 列表（不含明文） |
| POST | `/api/v1/agent/{id}/runtime-keys` | 新建（仅本次返回明文） |
| PUT | `/api/v1/agent/{id}/runtime-keys/{keyId}` | 改名/配额/启停 |
| DELETE | `/api/v1/agent/{id}/runtime-keys/{keyId}` | 删除 |
| GET | `/api/v1/agent/{id}/deployment/stats` | 平台网关按 Key 当日用量 |

> 配额与限流都是运行时单实例内存实现，重启容器后当日统计清零。

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

- 自动化：`DockerCliRuntimeTest` 3 个单测（create 参数、Docker 失败、inspect JSON）；CI `gateway` job 构建 `agent-gateway:ci` 后执行 `DockerCliRuntimeIT`，真实 Docker 完成 create→start→inspect→logs→stop→remove，并等 `/healthz` 返回 ok
- 服务编排：`AgentDeploymentServiceLifecycleTest` 12 个单测覆盖多副本 start/stop、remove 清理容器/Key/事件、未发布拦截、旧 gateway 多副本缺 Redis 拦截、nginx 模板不持有 Key/令牌、平台统计分支
- 平台运行时单测：`AgentRuntimeGatewayServiceTest` 6 个用例覆盖部署令牌、调用方 Key、停用/非法令牌、限流、日配额、平台用量统计

- 后端：`mvn -q -DskipTests compile` / `test-compile`，思考模式单测 7 + 路由单测 2 通过
- 部署链路（假 Docker CLI + 真实后端/数据库）：部署→运行态、端口 19000、环境变量/挂载/健康检查参数、停止/启动/日志、未发布拦截、删除并回收 Key，全部通过
- **以下 gateway/nginx 历史验证为迁移前结果；2026-10-31 后默认路径需重新做平台网关实测**：
- **真实 Docker（colima + agent-gateway:1）**：
  - 自研运行时：无令牌 401、正确令牌 200、SSE 对话正常、CORS 预检 204、限流 429、多 Key 配额与 `/stats` 验证通过
  - 调用方 Key：新建（配额 2）→ 调用 200/200/429 → `/stats` 显示名称与 2 次；停用后 401；删除后 401、主令牌仍 200；UI 新建→自动重建→停用→删除全流程通过
  - 多副本：部署 2 副本（19001/19000）均 healthy；Key 配额 4 跨副本 4 次 200、第 5 次 429；`/stats` 返回 `store:"redis"` 且计数共享；UI 显示副本输入 2、副本表 2 行、Key 用量 4
  - **内置 LB（2026-10-27 真实 Docker）**：2 副本自动创建 nginx 统一入口 19003；`/healthz`、`/v1/models` 经 LB 均 200；两个副本直连 19000/19002 均 200；停止/启动后 LB 状态同步为 STOPPED/RUNNING；删除部署后 LB 与副本容器全部清理
  - **资源边界（2026-10-27 真实 Docker）**：2 副本 + LB 的 `docker inspect` 均为 `NanoCpus=1000000000`（1 CPU）、`Memory=536870912`（512MiB）、`PidsLimit=256`、`SecurityOpt=[no-new-privileges]`
  - **聚合日志（2026-10-27 真实 Docker）**：`logs?all=true` 返回两个副本和 LB 三段日志；网关启动日志、`/healthz`、`/v1/models` 访问日志均可见
  - **Prometheus 指标（2026-10-27 真实部署）**：2 副本 + LB 时 `agent_deployment_running=1`、`replicas=2`、`ready_replicas=2`、`lb_running=1`；停止后 running/ready/lb 均为 0；删除后时间序列清理
  - **日志轮转（2026-10-27 真实 Docker）**：2 副本 + LB 的 `docker inspect` 均为 `log-driver=json-file`、`max-size=10m`、`max-file=3`
- **nginx 回退方案（colima + nginx:alpine）**：
  - `docker ps` → `aap-agent-1 Up (healthy) 0.0.0.0:19000->80/tcp`
  - `curl localhost:19000/healthz` → `ok`；`/v1/models` → `agent-1`
  - 容器内访问 `host.docker.internal:8080` 正常（模板与环境变量注入生效）
  - 非流式对话：1.7s 返回真实回答（DeepSeek）；SSE 流式分块正常
  - 停止/启动、`docker logs` 接口、Tailscale 地址 `http://100.82.118.40:19000/healthz` 均正常
  - 临时 Agent：发布 V1 → 部署 19001 → UI 再次发布 V2 → 自动重新部署（历史 DEPLOY/V1、DEPLOY/V2）→ 删除 Agent 后容器与部署记录一并清理
  - 访问令牌：无/错误令牌 401、正确令牌 200；CORS 预检 204 且返回允许头；并发 120 次 chat 中 99 次 429（限流生效）；重置令牌后旧令牌 401、新令牌 200；重启/重新部署不改变令牌（只有重置才变）
- 前端：`vue-tsc`、`vitest`、`vite build` 通过；Playwright 真页面走完「部署 → 运行中 → 日志 → 停止 → 启动 → 删除」，真实 Docker 下同样通过

## 下一步（如需）

1. K8s 实现：`ContainerRuntime` 增加 `KubernetesRuntime`，按租户配额创建 Deployment/Service/Ingress
2. 平台网关运行时：继续补真实 Docker/nginx 全链路回归与 token 级配额
3. 容器日志集中存储（Loki）
