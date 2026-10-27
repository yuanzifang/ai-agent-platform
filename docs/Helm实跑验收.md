# Helm Chart 首次实跑验收

日期：2026-10-26  
环境：macOS Apple Silicon + Colima + Docker 29.5.2 + k3d 5.9.0（k3s v1.35.5）+ Helm v4.3.1  
Chart：`deploy/helm/ai-agent-platform` 0.1.0  
代码基线：`15be67e`

## 结论

`helm upgrade --install --wait` 首次实际安装成功，应用与测试链路通过：

| 对象 | 结果 |
|---|---|
| release `aap` / namespace `aap-helm` | deployed |
| backend Deployment | 1/1 Ready，Flyway 89 个迁移完成 |
| frontend Deployment | 1/1 Ready |
| backend/frontend Service | ClusterIP 正常，Endpoints 已挂载 |
| `helm test` | `aap-test-connection` Phase Succeeded |
| 前端 `/healthz`、`/api/actuator/health` | 200 |
| `dev / Dev@123456` 登录 | 成功 |
| 真实 Ingress/TLS | 2026-10-27 补充验证：Traefik Ingress + 自签 TLS 通过 |
| 多副本 | 2026-10-27 补充验证：backend 2/2、frontend 2/2 Ready |

## 依赖与镜像

Chart 只包含后端/前端；本次使用：

- MySQL 8.4、Redis 7.4、PgVector 16：独立 Docker 容器，映射到宿主机端口后由集群通过 `host.k3d.internal` 访问
- MinIO：宿主机 Homebrew MinIO（官方镜像 403）
- Ollama：配置外部地址；本次登录/健康链路未调用 embedding
- 后端/前端镜像：本地构建后 `k3d image import` 导入
  - `aap-smoke-backend:latest`
  - `aap-smoke-frontend:latest`

## 安装命令（脱敏）

```bash
k3d cluster create aap-helm --servers 1 --agents 0 --wait \
  --k3s-arg '--disable-cloud-controller@server:0' \
  --k3s-arg '--disable=traefik@server:0' \
  --k3s-arg '--disable=metrics-server@server:0'

k3d image import aap-smoke-backend:latest aap-smoke-frontend:latest -c aap-helm

helm upgrade --install aap deploy/helm/ai-agent-platform \
  -n aap-helm --create-namespace \
  -f /tmp/aap-helm-values.yaml --wait --timeout 8m

kubectl -n aap-helm port-forward svc/aap-frontend 18091:80
helm test aap -n aap-helm
```

本地 values 关键覆盖：

- `backend.replicaCount=1`、`frontend.replicaCount=1`
- `ingress.enabled=false`、`networkPolicy.enabled=false`
- 外部 MySQL/Redis/PgVector/MinIO 地址
- `config.appEnv=development`、`config.authCookieSecure=false`（仅 HTTP 本地验收）
- 关闭 HPA/PDB（冒烟环境）

## 本次发现

1. **k3s 系统镜像无法从 Docker Hub 拉取**：`rancher/mirrored-pause:3.6`、CoreDNS、local-path-provisioner 拉取超时，导致 Pod 卡在 `ContainerCreating`。通过宿主机 `docker save` + `ctr -n k8s.io images import` 预载后解决；完全离线/受限网络环境需预载系统镜像。
2. **`helm test` 镜像**：`curlimages/curl:8.10.1` 同样被 Docker Hub 阻断，预载后 `helm test` 通过。
3. **Ingress 未启用**：本地无 ingress-nginx，使用 Service + port-forward；生产需单独验证 Ingress/TLS 与 SSE 会话保持。
4. Chart 默认 `backend.replicaCount=2`，本次降为 1；多副本会话保持仍未在 K8s 环境验证。

## 补充：Traefik Ingress + TLS + 2 副本（2026-10-27）

在 k3d/k3s 上额外安装了 Traefik（chart 42.1.3 / image 3.7.16），关闭 Chart 的 nginx 注解依赖：

- `ingress.className=traefik`，host `aap.local`
- 自签证书 `aap-tls`（CN/SAN=aap.local）
- backend **2/2**、frontend **2/2** Ready
- 通过 `kubectl port-forward -n traefik svc/traefik 18443:443` 访问
- `https://aap.local:18443/healthz` → 200
- `https://aap.local:18443/api/v1/auth/login` → 200，dev 登录成功
- 证书 subject/issuer 均为 `CN=aap.local`

限制：

- 测试使用 `appEnv=development`，因为宿主机 MinIO 使用开发凭据、DeepSeek 仍是 placeholder；生产 `ProductionGuard` 会拒绝。
- Traefik 多副本会话保持未做压力验证；Chart 的 backend Service ClientIP affinity 仍保留。
- `/api/actuator/health` 经 Ingress 前缀路径转发后不是 actuator 原路径，返回 401；健康检查应走 frontend `/healthz` 或后端 Service 直连。
- 真实域名/TLS 证书、托管依赖仍未验收。

## 仍缺

- 真实域名/TLS 证书、生产 Ingress Controller 参数（本地 Traefik 自签已通过）
- 托管 MySQL/Redis/PgVector/MinIO/Ollama 连接验收
- 多副本滚动更新、PDB、HPA、Traefik 下会话保持压力验证
- 完全离线环境的镜像清单/预载脚本
