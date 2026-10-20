# AI Agent Platform

企业级 AI Agent 平台（SaaS 多租户）：Spring Boot 3 + Spring AI（+ Spring AI Alibaba Graph）+ Vue3 + MySQL/Redis/PgVector/MinIO。
设计文档：`~/Desktop/AI-Agent-平台需求文档-v1.0.md`、`~/Desktop/AI-Agent-平台架构设计-v1.0.md`（内容 v1.2）。

## 技术决策（v1.2 定稿）

- 框架：Spring AI 1.0.x 基础框架 + Spring AI Alibaba（Graph 多 Agent 编排，M2+ 引入）
- Chat 模型：DeepSeek（OpenAI 兼容协议），API Key 经 `DEEPSEEK_API_KEY` 注入（占位，后补）
- 嵌入模型：本地 Ollama `nomic-embed-text`（768 维，零成本），`ollama.endpoint` 默认 http://localhost:11434
- 语音转文字：本地 ASR 服务（接口样例后补，当前 `asr.enabled=false`）
- 开发模式：本机直跑（无需 Docker）；Docker Compose 仅服务器部署用

## 目录

- `backend/` Spring Boot 单体（模块化包：auth / system / agent / knowledge / ...）
- `frontend/` Vue3 + TS + Vite + Element Plus

## 本机开发准备（macOS，无 Docker）

```bash
brew install mysql redis postgresql@16 pgvector minio ollama
brew services start mysql redis postgresql@16

# MySQL 建库
mysql -uroot -e "CREATE DATABASE ai_agent_platform DEFAULT CHARACTER SET utf8mb4;"

# PgVector 建库（向量库）
psql -d postgres -c "CREATE DATABASE vector;"
# pgvector 扩展：psql -d vector -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 本地嵌入模型
ollama pull nomic-embed-text
```

## 环境变量（后端）

| 变量 | 说明 | 默认 |
|---|---|---|
| MYSQL_PASSWORD | MySQL 密码 | root |
| JWT_SECRET | JWT 签名密钥（≥32 字节） | dev 占位（生产必换） |
| PLATFORM_ADMIN_PASSWORD | 平台管理员初始密码（首次启动重置 seed 账号） | 无则保留占位 |
| DEEPSEEK_API_KEY | DeepSeek Key（后补） | 空（相关功能提示未配置） |

## 启动

```bash
# 后端（M1：认证/多租户/RBAC/审计）
cd backend && mvn spring-boot:run
# 平台管理员：admin / $PLATFORM_ADMIN_PASSWORD（未设置时启动日志会提示）

# 前端
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## 服务器部署（Docker Compose）

前置：Docker Engine / Docker Desktop + Compose v2。

```bash
# 一键生成 .env.docker（强随机密码）、构建并启动
scripts/docker-up.sh

# 或手动
cp .env.docker.example .env.docker
# 编辑 .env.docker：至少填写 DEEPSEEK_API_KEY
docker compose --env-file .env.docker up -d --build
docker compose --env-file .env.docker exec ollama ollama pull nomic-embed-text
```

启动后：

- 前端：`http://<服务器IP>:80`
- 管理员：`admin / <PLATFORM_ADMIN_PASSWORD>`（在 `.env.docker` 中）
- MinIO 控制台：`http://<服务器IP>:9001`
- 后端 8080、MySQL、Redis、PgVector 默认只在内网，不直接暴露
- 数据卷：`mysql-data / redis-data / pgvector-data / minio-data / ollama-data`

常用命令：

```bash
docker compose --env-file .env.docker ps
docker compose --env-file .env.docker logs -f backend
scripts/docker-down.sh             # 停止，保留数据
scripts/docker-down.sh --volumes   # 停止并删除数据（需输入 DELETE）
```

可观测性：

- Prometheus：`http://127.0.0.1:9090`（默认只绑定本机，远程用 SSH 隧道或改 `PROMETHEUS_BIND`）
- Grafana：`http://127.0.0.1:3000`，账号 `admin / <GRAFANA_ADMIN_PASSWORD>`
- 已预置 `AI Agent Platform Overview` 仪表盘（请求速率、5xx、P95、JVM 内存、CPU）
- 后端指标端点 `/actuator/prometheus` 仅容器内网供 Prometheus 抓取，Nginx 不反代
- 需要远程访问时把 `PROMETHEUS_BIND/GRAFANA_BIND` 改为 `0.0.0.0`，并只对可信网段开放端口

生产建议：

- 只开放 80/443；MinIO 控制台 9001 仅内网访问
- 前面再加 HTTPS/负载均衡；SSE 需关闭代理缓冲（Nginx 配置已设置）
- `TOOL_ALLOW_PRIVATE=false`，需要访问内网工具时用 `TOOL_ALLOWED_HOSTS` 白名单
- 定期备份 MySQL/PgVector/MinIO 数据卷，并定期做恢复演练
- 当前 Compose 为单机部署；K8s/Helm 多实例部署为后续项

## 备份与恢复

```bash
./scripts/backup.sh                 # MySQL + PgVector + MinIO 全量备份
./scripts/restore-drill.sh          # 自动恢复演练（临时库/临时 bucket，不碰生产）
```

备份产物包含 `manifest.json` 和 `SHA256SUMS`，默认落在 `backups/`（已 gitignore）。详细配置、定时任务、生产恢复步骤与边界见 [`docs/备份与恢复.md`](docs/备份与恢复.md)。

大表分区与未来分区自动补齐见 [`docs/大表分区.md`](docs/大表分区.md)；V48 覆盖消息、会话、Trace、Span、审计和系统日志。 消息 90 天归档见 [`docs/消息归档.md`](docs/消息归档.md)；平台管理员可手动触发归档，默认每日 03:40 执行。 审计 180 天归档与链锚点见 [`docs/审计归档.md`](docs/审计归档.md)。

## Kubernetes / Helm

生产多副本建议用 Helm Chart（后端/前端；MySQL/Redis/PgVector/MinIO/Ollama 使用托管服务或集群内独立服务）：

```bash
# 构建并推送镜像后
helm upgrade --install ai-agent deploy/helm/ai-agent-platform \
  -n ai-agent --create-namespace \
  --set backend.image.repository=registry.example.com/ai-agent/backend \
  --set backend.image.tag=0.1.0 \
  --set frontend.image.repository=registry.example.com/ai-agent/frontend \
  --set frontend.image.tag=0.1.0 \
  --set config.mysqlHost=mysql \
  --set config.redisHost=redis \
  --set config.pgUrl=jdbc:postgresql://pgvector:5432/vector \
  --set config.minioEndpoint=http://minio:9000 \
  --set config.ollamaEndpoint=http://ollama:11434 \
  --set secrets.mysqlPassword='***' \
  --set secrets.pgPassword='***' \
  --set secrets.minioAccessKey='***' \
  --set secrets.minioSecretKey='***' \
  --set secrets.jwtSecret='***' \
  --set secrets.aesKey='***' \
  --set secrets.platformAdminPassword='***'
```

- Ingress 默认 `/api` → backend，`/` → frontend，SSE 已关闭代理缓冲
- 支持 HPA/PDB/NetworkPolicy/ServiceAccount
- 生产建议 `secrets.existingSecret` 引用 Secret，不把密码写进 values
- 多副本 SSE 停止/重生成依赖 ClientIP 会话保持；如不生效请先将 backend 副本设为 1
- 详见 `deploy/helm/ai-agent-platform/README.md`

## 里程碑

M1 平台基座（当前）→ M2 模型与 Agent → M3 知识库 → M4 会话对话 → M5 可观测 → M6 安全合规验收。
每个里程碑对应一批 Flyway 迁移脚本（V1 为 M1 表结构，后续里程碑递增）。
