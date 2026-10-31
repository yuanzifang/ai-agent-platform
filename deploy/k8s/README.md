# K8s / K3s 标准部署

本目录提供平台后端、前端的标准镜像构建和 Helm 部署方式。平台依赖的
MySQL / Redis / PgVector / MinIO / Elasticsearch / Qdrant 建议使用外部服务，
通过 Helm values 注入地址；Chart 目前只负责应用层。

## 1. 构建标准镜像

镜像标签建议使用发布版本，例如 `0.1.0`：

```bash
TAG=0.1.0 ./deploy/k8s/build-images.sh
```

生成：

```text
aap/backend:0.1.0
aap/frontend:0.1.0
```

如果使用远程 Docker（例如 UTM Docker），先设置 `DOCKER_HOST` 即可复用脚本。

## 2. 创建命名空间和 Secret

Secret key 与 Chart 保持一致：

```bash
kubectl create namespace aap

kubectl -n aap create secret generic aap-secrets \
  --from-literal=MYSQL_PASSWORD='***' \
  --from-literal=PG_PASSWORD='***' \
  --from-literal=REDIS_PASSWORD='***' \
  --from-literal=MINIO_ACCESS_KEY='***' \
  --from-literal=MINIO_SECRET_KEY='***' \
  --from-literal=JWT_SECRET='***' \
  --from-literal=AES_KEY='***' \
  --from-literal=PLATFORM_ADMIN_PASSWORD='***' \
  --from-literal=DEEPSEEK_API_KEY='***'
```

`AES_KEY` 至少 32 字符。若历史数据使用了 JWT_SECRET 作为兜底密钥，
部署时应保持与历史环境一致。

## 3. 部署

复制示例 values，修改外部服务地址和镜像标签：

```bash
cp deploy/k8s/values-utm.example.yaml /tmp/aap-values.yaml
helm upgrade --install aap deploy/helm/ai-agent-platform \
  -n aap -f /tmp/aap-values.yaml --wait --timeout 10m
```

检查：

```bash
kubectl -n aap get pods -o wide
kubectl -n aap rollout status deploy/aap-ai-agent-platform-backend
kubectl -n aap rollout status deploy/aap-ai-agent-platform-frontend
```

## 4. 无 Ingress 时访问

Chart 默认关闭 Ingress，适合单机 K3s/k3d 或内网验证。

UTM 当前使用 NodePort：

```text
http://192.168.64.2:18080
```

对应关系：K8s NodePort `30080` → k3d 主机端口 `18080`。

如果不使用 NodePort，也可以临时 port-forward：

```bash
kubectl -n aap port-forward svc/aap-ai-agent-platform-frontend 18080:80
```

生产环境建议启用 Ingress，并把 `config.publicBase` / `config.frontendBase`
改为正式域名。

## 5. UTM 单机 k3d 示例

当前 UTM 上的集群名为 `aap-k8s`，API 只监听 UTM 本机 `6443`。Mac 侧通过
SSH 本地转发使用：

```bash
ssh -fN -i ~/.ssh/aap-utm \
  -L 127.0.0.1:16443:127.0.0.1:6443 \
  fangyuan@192.168.64.2

kubectl config set-cluster k3d-aap-k8s \
  --server=https://127.0.0.1:16443
```

如果使用 NodePort `30080`，把它映射到 UTM 宿主机端口：

```bash
k3d cluster edit aap-k8s --port-add 0.0.0.0:18080:30080@server:0
```

构建并导入：

```bash
export DOCKER_HOST=unix:///tmp/aap-utm-docker.sock
TAG=0.1.0 ./deploy/k8s/build-images.sh
k3d image import aap/backend:0.1.0 aap/frontend:0.1.0 -c aap-k8s
```

如果节点无法从 Docker Hub 拉取系统镜像，需要额外把 pause、CoreDNS、
local-path-provisioner、metrics-server 预载到 k3s containerd。

## 6. 外部依赖

示例 values 使用 UTM 宿主机的 Docker 服务地址。K3s/k3d 节点可以通过
`host.k3d.internal` 或宿主机内网 IP 访问这些服务。

当前 UTM 单机基础设施端口：

| 服务 | 地址 |
|---|---|
| MySQL | `192.168.64.2:3306` |
| Redis | `192.168.64.2:6379` |
| PostgreSQL + pgvector | `192.168.64.2:5432` |
| MinIO | `http://192.168.64.2:9000` |
| Elasticsearch | `http://192.168.64.2:9200` |
| Ollama（独立原生） | `http://192.168.64.1:11434` |
| Qdrant | `http://192.168.64.2:6333` |
| Agent Gateway | `http://192.168.64.2:19001` |

如果迁移到云托管，替换 values 中的地址即可，不需要重打应用镜像。

Ollama 不放在 Docker/Compose/K8s 中，作为独立原生服务运行。当前 Mac 上的 Ollama 默认只监听 `127.0.0.1:11434`，因此使用一个仅绑定 `192.168.64.1:11434` 的本地 TCP 代理供 K8s 访问；不要把 Ollama 镜像加入集群。

## 7. 升级和回滚

```bash
helm upgrade aap deploy/helm/ai-agent-platform -n aap -f /tmp/aap-values.yaml
helm history aap -n aap
helm rollback aap <revision> -n aap
```

升级前建议先构建并推送新 tag，或者先把镜像导入目标节点。

## 8. 集群内状态服务

如果希望应用不依赖 UTM Docker，可使用 `deploy/k8s/infra/stateful.yaml`
部署 MySQL/Redis/PG/MinIO/ES/Qdrant，并使用
`deploy/k8s/values-utm-incluster.example.yaml`。

```bash
kubectl apply -f deploy/k8s/infra/stateful.yaml
helm upgrade --install aap deploy/helm/ai-agent-platform \
  -n aap -f deploy/k8s/values-utm-incluster.example.yaml --wait
```

当前 UTM 已按这个模式运行；旧 Docker 栈已移除，迁移前备份保留在 Mac。详见
`deploy/k8s/infra/README.md`。Ollama 不属于该清单，仍独立原生运行。

## 9. 多节点测试

当前 UTM 集群已从单节点扩到 1 server + 2 agents：

```bash
k3d node create aap-k8s-agent-0 --cluster aap-k8s --role agent --wait
k3d node create aap-k8s-agent-1 --cluster aap-k8s --role agent --wait
```

导入应用镜像：

```bash
k3d image import aap/backend:0.1.0 aap/frontend:0.1.0 aap/agent-gateway:1 -c aap-k8s
```

如果新 agent 拉取 pause 失败，需要把 `rancher/mirrored-pause:3.6` 直接导入每个节点
的 `k8s.io` containerd namespace，然后扩应用副本：

```bash
kubectl -n aap scale deploy/aap-ai-agent-platform-backend --replicas=2
kubectl -n aap scale deploy/aap-ai-agent-platform-frontend --replicas=2
kubectl get pods -n aap -o wide
```

注意：状态服务当前使用 `local-path` PVC，数据 Pod 固定在 server 节点；这是应用层多节点
调度测试，不是数据库高可用。生产需要分布式存储或对应数据库 Operator。
