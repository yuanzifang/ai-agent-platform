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

### 3.1 初始化向量库专用账号

应用部署前，用 PostgreSQL 管理员账号创建专用角色：

```bash
APP_DB_ROLE=aap_vector_app APP_DB_PASSWORD='***' \
PGHOST=127.0.0.1 PGPORT=5432 PGUSER=aiagent PGDATABASE=vector \
scripts/vector-role.sh --apply

APP_DB_ROLE=aap_vector_app PGHOST=127.0.0.1 PGPORT=5432 \
PGUSER=aiagent PGDATABASE=vector scripts/vector-grants.sh --apply
```

集群内执行时可通过 `kubectl port-forward svc/pgvector 5432:5432 -n aap-data`，或直接在 `pgvector-0` 内执行生成的 SQL。角色密码需与应用 Secret 的 `PG_PASSWORD` 一致。

## 4. 导入数据

新环境可以从空库启动；已有平台数据时按以下方式导入：

- MySQL：用 `mysqldump --single-transaction` 导出后导入 `mysql-0`
- PostgreSQL：用 `pg_dump` 导出后导入 `pgvector-0`
- Redis：把 RDB 放入 `data-redis-0` 所属 PVC，再用 AOF 模式启动
- MinIO：通过 S3 API 用 `mc mirror` 同步 bucket
- Qdrant：复制 Qdrant storage，或使用 snapshot API
- Elasticsearch：使用 snapshot API，或按索引重建

当前 UTM 的迁移过程保留了原 UTM Docker 栈作为回滚，不删除源数据。


## 5. 当前切换结果

UTM 当前已切到 K8s 主入口：

- 应用：`http://192.168.64.2:18080`
- Agent 运行时由平台网关 + 每 Agent nginx 入口提供，不再单独部署 Node Gateway
- Ollama：独立原生服务，`http://192.168.64.1:11434`
- 旧 UTM Docker 栈已移除，旧数据卷已删除；迁移前备份仍保留在 Mac 的备份目录中。

## 6. 应用到集群内依赖

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
