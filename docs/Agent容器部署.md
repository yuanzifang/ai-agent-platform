# Agent 容器部署（MVP：本机 Docker）

> 2026-10-31 MVP 冻结：默认 `AGENT_DEPLOY_ENABLED=false`，部署/重新部署 API 会拒绝；已有部署仍可查看、停止、删除。需要启用时显式设置 `AGENT_DEPLOY_ENABLED=true`，并建议同时设置 `AGENT_DEPLOY_BIND=127.0.0.1`、`AGENT_DEPLOY_CORS_ORIGINS`。
>
> 默认 `gateway` runtime 的 API Key 和运行时令牌通过只读 Secret 文件传入（`API_KEY_FILE` / `RUNTIME_TOKEN_FILE`），不再写入容器环境变量；旧 `nginx` fallback 仍使用环境变量，仅保留兼容。

目标：**发布 → 一键部署 → 容器里跑起来 → 直接调用**。

> 外部 Docker（UTM/Docker Desktop）必须把 `AGENT_DEPLOY_WORKDIR` 配成宿主共享目录；后端容器写入的 `secrets/<container>/api-key`、`runtime-token` 只有被 Docker 守护进程看到，才能以只读文件挂载进 Agent 容器。若后端运行在平台容器内而 Docker 在宿主，需为该目录配置共享卷，不能使用容器内私有路径。

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
4. 调用示例（需带访问令牌；平台 Key 已注入容器，不需要再传 X-Api-Key）：

```bash
curl -s http://100.82.118.40:19000/v1/chat/completions \
  -H 'Authorization: Bearer <访问令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"model":"agent","messages":[{"role":"user","content":"你好"}]}'
```

令牌在部署弹窗里可直接复制；「重置令牌」会立即作废旧令牌并重建容器。流式（SSE）同样支持：加 `"stream": true`。

## 实现方式

默认使用**自研运行时镜像** `aap/agent-gateway:1`（见 `docs/Agent运行时镜像.md`），每个 Agent 至少一个运行时容器；多副本时额外一个 nginx LB 容器：

```
client → container:8080 /v1/chat/completions
       → 校验运行时令牌 / 限流 / 日配额
       → 平台 /api/v1/open/chat/completions（自动带 X-Api-Key）
       → 透传响应（含 SSE）
```

- 先在仓库构建镜像：`docker build -t aap/agent-gateway:1 runtime/agent-gateway`
- 部署时自动创建该 Agent 的集成 API Key（`container:aap-agent-<id>`），删除/重新部署时清理
- 镜像内置鉴权、限流、CORS、`/stats` 用量统计，不挂载宿主机目录
- 兼容旧的 nginx 方案：`agent.deploy.runtime=nginx` 时仍使用 `nginx:alpine` + 模板挂载
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
| `runtime` | `AGENT_DEPLOY_RUNTIME` | `gateway` | `gateway`=自研运行时，`nginx`=旧模板方案 |
| `gateway-image` | `AGENT_DEPLOY_GATEWAY_IMAGE` | `aap/agent-gateway:1` | 自研运行时镜像 |
| `image` | `AGENT_DEPLOY_IMAGE` | `nginx:alpine` | nginx 回退方案镜像 |
| `caller-quota-daily` | `AGENT_DEPLOY_CALLER_QUOTA_DAILY` | 0 | 主令牌日配额（0=不限） |
| `caller-keys` | `AGENT_DEPLOY_CALLER_KEYS` | 空 | 额外调用方 Key：`key1:配额,key2:0` |
| `replicas` | `AGENT_DEPLOY_REPLICAS` | 1 | 默认副本数（1-5）；部署时可单独指定 |
| `redis-url` | `AGENT_DEPLOY_REDIS_URL` | `redis://host.docker.internal:6379/0` | 多副本共享限流/配额/用量 |
| `port-start/port-end` | `AGENT_DEPLOY_PORT_START/END` | 19000-19999 | 宿主端口池（自动挑空端口） |
| `bind` | `AGENT_DEPLOY_BIND` | `0.0.0.0` | 端口绑定地址；改 `127.0.0.1` 则只允许本机 |
| `cpu-limit` | `AGENT_DEPLOY_CPU_LIMIT` | `1` | 每个运行时/LB 容器的 CPU 上限（Docker `--cpus`） |
| `memory-limit` | `AGENT_DEPLOY_MEMORY_LIMIT` | `512m` | 每个运行时/LB 容器的内存上限 |
| `pids-limit` | `AGENT_DEPLOY_PIDS_LIMIT` | `256` | 每个容器的 PID 上限，防止进程失控 |
| `log-max-size` | `AGENT_DEPLOY_LOG_MAX_SIZE` | `10m` | Docker json-file 单文件日志上限 |
| `log-max-file` | `AGENT_DEPLOY_LOG_MAX_FILE` | `3` | Docker 日志保留文件数 |
| `platform-base-url` | `AGENT_DEPLOY_PLATFORM_BASE` | `http://host.docker.internal:8080` | 容器访问平台的地址 |
| `rate-limit-per-min` | `AGENT_DEPLOY_RATE_LIMIT` | 60 | 每 IP 每分钟请求数（作用于 /v1/ 代理请求） |
| `rate-limit-burst` | `AGENT_DEPLOY_RATE_BURST` | 20 | 突发配额，超出返回 429 |
| `cors-origins` | `AGENT_DEPLOY_CORS_ORIGINS` | `*` | 允许的跨域来源 |
| `public-base` | `AGENT_DEPLOY_PUBLIC_BASE` | 空 | 对外地址前缀，空则自动取 Tailscale/内网 IP |
| `workdir` | `AGENT_DEPLOY_WORKDIR` | `~/.aap-agent-runtime` | 模板和只读 Secret 文件目录；必须位于 Docker 守护进程可见的共享路径（外部 UTM Docker 需要显式配置卷/共享目录） |
| `docker-bin` | `AGENT_DEPLOY_DOCKER_BIN` | `docker` | Docker CLI 路径 |

> `host.docker.internal` 在 Docker Desktop / colima 下可用；Linux 上已通过 `--add-host host.docker.internal:host-gateway` 兜底。若后端本身跑在 compose 里，请把 `platform-base-url` 改成平台在容器网络中的地址（如 `http://backend:8080`）。

## 多副本

部署弹窗可设置「副本数」（1-5），每个副本一个容器、独立端口：

- 副本明细在弹窗内展示（容器名/端口/状态/地址），`agent_deployment` 主行保存第一个副本，明细存 `replicas_json`
- 启停/删除/日志/统计会作用于全部副本
- **多副本必须配置 Redis**（`agent.deploy.redis-url`），限流、日配额与用量跨副本共享；未配置时只允许 1 副本
- 多副本自动创建一个 nginx LB 容器（`aap-agent-<id>-lb`，迁移 V100），对外暴露稳定「统一入口」，按 `least_conn` 轮询副本；副本故障时自动转发到健康副本
- 统一入口地址写入 `lb_endpoint`/`lb_local_endpoint`，部署弹窗的对外地址和 curl 示例优先使用 LB；副本表仍展示各自端口

## 调用方 Key 与日配额

部署弹窗的「调用方 Key」面板（gateway 运行时时可用）：

- **新建 Key**：名称 + 日配额（0=不限），明文只显示一次，可复制给调用方
- 列表显示：名称、前缀、日配额、**今日调用**、状态；支持编辑、停用/启用、删除
- 每次变更都会**自动重建容器**使 Key 生效；未部署时保存，部署后生效
- 调用方式：`Authorization: Bearer sk-run-...`（与部署主令牌等价，独立配额）
- 平台只把 **SHA-256 哈希 + 配额 + 名称** 注入容器（`CALLER_KEYS_JSON`），容器内没有明文
- 「今日调用」来自容器 `/stats`，按 UTC 日重置；主令牌用量同时显示

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/agent/{id}/runtime-keys` | Key 列表（不含明文） |
| POST | `/api/v1/agent/{id}/runtime-keys` | 新建（仅本次返回明文） |
| PUT | `/api/v1/agent/{id}/runtime-keys/{keyId}` | 改名/配额/启停 |
| DELETE | `/api/v1/agent/{id}/runtime-keys/{keyId}` | 删除 |
| GET | `/api/v1/agent/{id}/deployment/stats` | 代理容器 `/stats`，按 Key 当日用量 |

> 配额与限流都是运行时单实例内存实现，重启容器后当日统计清零。

## 安全说明

- 容器接口需要 `Authorization: Bearer <访问令牌>`；令牌随部署生成、加密存储，可一键重置（重置即重建容器）。`/healthz` 不需要令牌，供容器 healthcheck 使用。
- 运行时网关支持多调用方 Key（UI 管理，容器内只存哈希）与各自日配额，`/stats` 查看当日用量；限流按 Key 令牌桶，超出返回 429。
- CORS 默认 `*`，生产建议改成具体前端域名。
- API Key 与令牌以环境变量形式存在容器里，`docker inspect` 可见；删除部署时会一并删除该 Key。
- 默认绑定 `0.0.0.0` 是为了 Tailscale/手机访问；仍建议只在内网/网关后面暴露该端口。
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
- 服务编排：`AgentDeploymentServiceLifecycleTest` 4 个单测覆盖多副本 start/stop、remove 清理容器/Key/事件、未发布拦截、多副本缺 Redis 拦截

- 后端：`mvn -q -DskipTests compile` / `test-compile`，思考模式单测 7 + 路由单测 2 通过
- 部署链路（假 Docker CLI + 真实后端/数据库）：部署→运行态、端口 19000、环境变量/挂载/健康检查参数、停止/启动/日志、未发布拦截、删除并回收 Key，全部通过
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
2. 运行时多副本：token 级配额（限流/日配额/统计已 Redis 共享）
3. 容器日志集中存储（Loki）
