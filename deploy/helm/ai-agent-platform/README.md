# AI Agent Platform Helm Chart

部署后端与前端（多副本），依赖外部或集群内独立的 MySQL / Redis / PgVector / MinIO / Ollama。

## 1. 构建并推送镜像

```bash
docker build -t registry.example.com/ai-agent/backend:0.1.0 backend
docker build -t registry.example.com/ai-agent/frontend:0.1.0 frontend
docker push registry.example.com/ai-agent/backend:0.1.0
docker push registry.example.com/ai-agent/frontend:0.1.0
```

## 2. 准备依赖

推荐使用云托管服务；也可以自行安装：

- MySQL 8.x，库 `ai_agent_platform`，用户需有建表/迁移权限（Flyway）
- Redis 7.x（建议设置密码）
- PostgreSQL 16 + pgvector 扩展，库 `vector`
- MinIO 或 S3 兼容对象存储，bucket `ai-agent-platform`（后端启动时会自动创建）
- Ollama（或兼容的 Embedding 服务），默认优先 `bge-m3`；未安装时自动回退 `nomic-embed-text`，建议两者都拉取

如果使用集群内 Bitnami 等 Chart，请把 `config.mysqlHost/redisHost/pgUrl/minioEndpoint/ollamaEndpoint` 改成对应 Service 名称。

## 3. 安装

```bash
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
  --set secrets.platformAdminPassword='***' \
  --set secrets.deepseekApiKey='***'
```

生产建议用 `secrets.existingSecret` 引用预先创建的 Secret：

```bash
kubectl -n ai-agent create secret generic ai-agent-secrets \
  --from-literal=MYSQL_PASSWORD='***' \
  --from-literal=REDIS_PASSWORD='***' \
  --from-literal=PG_PASSWORD='***' \
  --from-literal=MINIO_ACCESS_KEY='***' \
  --from-literal=MINIO_SECRET_KEY='***' \
  --from-literal=JWT_SECRET='***' \
  --from-literal=AES_KEY='***' \
  --from-literal=DEEPSEEK_API_KEY='***' \
  --from-literal=PLATFORM_ADMIN_PASSWORD='***' \
  --from-literal=SMTP_USERNAME='' \
  --from-literal=SMTP_PASSWORD=''

helm upgrade --install ai-agent deploy/helm/ai-agent-platform \
  -n ai-agent --set secrets.existingSecret=ai-agent-secrets ...
```

Secret key 名称固定为：

`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`PG_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、
`JWT_SECRET`、`AES_KEY`、`DEEPSEEK_API_KEY`、`PLATFORM_ADMIN_PASSWORD`、`SMTP_USERNAME`、`SMTP_PASSWORD`。

## 4. Ingress / TLS

`values.yaml` 默认开启 Ingress，`/api` 直接转发后端，`/` 转发前端：

- `/api` → backend:8080
- `/` → frontend:80

SSE 所需注解已默认配置（proxy-buffering off、read/send timeout 300s、body 20m）。

## 5. 验证

```bash
helm lint deploy/helm/ai-agent-platform -f deploy/helm/ai-agent-platform/values.yaml
helm template ai-agent deploy/helm/ai-agent-platform -n ai-agent -f my-values.yaml > /tmp/render.yaml
kubectl -n ai-agent get pods
helm test ai-agent -n ai-agent
```

## 6. 多副本注意事项

- 审计哈希链使用 MySQL 租户行锁；定时任务/指标汇总使用 Redis 互斥。
- SSE "停止生成/重新生成" 使用单实例内存中的流句柄；Service 默认 `ClientIP` 会话保持。若 Ingress/负载均衡不保持连接，先将 `backend.replicaCount` 设为 1，或引入 Redis/消息总线共享流状态。
- Flyway 会在每个后端副本启动时执行；Flyway 自带锁表，能串行迁移，但发布时建议先跑一次 Job 或短暂单副本启动。
- 技能脚本在容器内以 Python/Node 进程执行；生产建议替换为隔离执行器（Firecracker/gVisor/K8s Job）。
- 当前 Chart 未包含 MySQL/Redis/PgVector/MinIO/Ollama 的 StatefulSet；全量单机请用根目录 `docker-compose.yml`。

## 7. 本地 k3d 实跑记录

2026-10-26 已在 k3d 5.9.0 / k3s v1.35.5 上完成首次 install 与 `helm test`，详见 `docs/Helm实跑验收.md`。

受限网络下需先预载 k3s 系统镜像（pause/CoreDNS/local-path-provisioner）和 test 镜像 `curlimages/curl`，否则 Pod 会卡在 `ContainerCreating`。本地冒烟可关闭 Ingress 后用 `kubectl port-forward svc/<release>-frontend 18091:80` 验证。
