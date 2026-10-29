# Alert HA 本地模板

本目录提供两套应用层 HA 模板：

- `docker-compose.yml`：relay 双副本 + nginx LB + Alertmanager 三节点
- `k8s.yaml`：relay 两副本 + Alertmanager 三节点 StatefulSet

Redis 为外部共享状态，生产请使用 Redis Sentinel/Cluster 或托管 Redis。钉钉/邮件/短信凭证通过环境变量或 K8s Secret 注入，不写入仓库。

## Compose

```bash
cd deploy/alert-ha
ALERT_RELAY_ACK_TOKEN='change-me' \
REDIS_HOST=host.docker.internal \
docker compose -f docker-compose.yml up -d --build
```

- relay LB：`127.0.0.1:18099`
- Alertmanager：`127.0.0.1:19093/19094/19095`
- 三节点完成 gossip 后互相转发；停掉任一节点仍可投递
- `alertmanager.yml` 的 receiver 指向 `alert-relay-lb:8080`

## K8s

```bash
kubectl apply -f k8s.yaml
```

先按 `k8s.yaml` 中的 Secret 模板创建真实的 `aap-alert-ha-secret`；Relay 镜像 `ghcr.io/example/ai-agent-platform-alert-relay:0.1.0` 需替换为实际镜像。Redis 默认使用 `redis:6379`，生产应改为托管 Redis。

## 验证

Compose 双副本/三节点和本地故障切换已于 2026-10-28 实跑，见 `docs/告警HA演练.md`。模板化部署需在目标 Docker/K8s 环境再跑一次。
