# K8s 状态服务

`stateful.yaml` 提供单节点环境使用的 StatefulSet + PVC：

- MySQL 8.4
- Redis 8.10
- PostgreSQL 16 + pgvector
- MinIO
- Elasticsearch 8.15
- Qdrant

Ollama 不在此清单中，按独立原生服务处理。

## 1. 创建 Secret

```bash
kubectl create namespace aap-data

kubectl -n aap-data create secret generic aap-data-secrets \
  --from-literal=MYSQL_ROOT_PASSWORD='***' \
  --from-literal=PG_PASSWORD='***' \
  --from-literal=REDIS_PASSWORD='***' \
  --from-literal=MINIO_ROOT_USER='***' \
  --from-literal=MINIO_ROOT_PASSWORD='***'
```

## 2. 预载镜像

如果使用 k3d/k3s 且节点不能拉取 Docker Hub，需要预载：

```bash
for img in \
  mysql:8.4 \
  redis:8.10-alpine \
  pgvector/pgvector:pg16 \
  bitnamilegacy/minio:latest \
  docker.elastic.co/elasticsearch/elasticsearch:8.15.0 \
  qdrant/qdrant:latest \
  rancher/mirrored-library-busybox:1.37.0; do
  docker pull "$img"
done

k3d image import \
  mysql:8.4 redis:8.10-alpine pgvector/pgvector:pg16 \
  bitnamilegacy/minio:latest \
  docker.elastic.co/elasticsearch/elasticsearch:8.15.0 \
  qdrant/qdrant:latest rancher/mirrored-library-busybox:1.37.0 \
  -c aap-k8s
```

## 3. 部署

```bash
kubectl apply -f deploy/k8s/infra/stateful.yaml
kubectl -n aap-data get pods,pvc -o wide
```

需要先确保集群有 `local-path` StorageClass，或者把 PVC 的
`storageClassName` 改成目标集群的 StorageClass。

## 4. 导入数据

新环境可以从空库启动；已有平台数据时按以下方式导入：

- MySQL：用 `mysqldump --single-transaction` 导出后导入 `mysql-0`
- PostgreSQL：用 `pg_dump` 导出后导入 `pgvector-0`
- Redis：把 RDB 放入 `data-redis-0` 所属 PVC，再用 AOF 模式启动
- MinIO：通过 S3 API 用 `mc mirror` 同步 bucket
- Qdrant：复制 Qdrant storage，或使用 snapshot API
- Elasticsearch：使用 snapshot API，或按索引重建

当前 UTM 的迁移过程保留了原 UTM Docker 栈作为回滚，不删除源数据。


## 5. Agent Gateway

统计/运行时网关是无状态服务，可单独部署到 K8s。创建 Secret：

```bash
kubectl -n aap create secret generic aap-agent-secrets \
  --from-literal=API_KEY='***' \
  --from-literal=RUNTIME_TOKEN='***' \
  --from-literal=REDIS_URL='redis://:<redis-password>@redis.aap-data.svc.cluster.local:6379/0'

kubectl apply -f deploy/k8s/infra/agent-gateway.yaml
```

`agent-gateway.yaml` 使用：

- 后端：`aap-ai-agent-platform-backend.aap.svc.cluster.local:8080`
- Redis：`redis.aap-data.svc.cluster.local:6379`
- 对外 NodePort：`30090`

UTM 单机 k3d 可将 NodePort 映射到原端口：

```bash
k3d cluster edit aap-k8s --port-add 0.0.0.0:19001:30090@server:0
```

## 6. 当前切换结果

UTM 当前已切到 K8s 主入口：

- 应用：`http://192.168.64.2:18080`
- Agent Gateway：`http://192.168.64.2:19001`
- Ollama：独立原生服务，`http://192.168.64.1:11434`
- 旧 UTM Docker 栈已移除，旧数据卷已删除；迁移前备份仍保留在 Mac 的备份目录中。

## 7. 应用到集群内依赖

使用：

```text
deploy/k8s/values-utm-incluster.example.yaml
```

应用配置通过以下 Service DNS 访问：

```text
mysql.aap-data.svc.cluster.local
redis.aap-data.svc.cluster.local
pgvector.aap-data.svc.cluster.local
minio.aap-data.svc.cluster.local
elasticsearch.aap-data.svc.cluster.local
qdrant.aap-data.svc.cluster.local
```

Ollama 仍使用独立地址，例如 `http://192.168.64.1:11434`。

## 8. 注意

- 这是单节点本地存储方案，不是生产高可用数据库集群。
- 生产建议使用云数据库/托管 Redis/对象存储，或使用对应 Operator + 多副本。
- PVC 由 `local-path` 提供，Pod 重建后数据保留；删除 PVC 会删除数据。
