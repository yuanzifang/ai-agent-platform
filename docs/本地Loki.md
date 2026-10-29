# 本地 Loki 日志平台

## 组件

```text
Docker json-file 日志
  → Promtail（Docker SD，读取容器日志）
  → Loki（filesystem + TSDB，默认保留 7 天）
  → Grafana Loki 数据源 / 日志看板
```

相关文件：

- `deploy/observability/loki.yml`
- `deploy/observability/promtail.yml`
- `deploy/observability/grafana/provisioning/datasources/loki.yml`
- `deploy/observability/grafana/dashboards/logs.json`
- `docker-compose.yml` 的 `loki` / `promtail` 服务

## 启动

完整 Compose 启动（需要 `.env.docker` 中的数据库/密钥）：

```bash
docker-compose up -d loki promtail grafana
```

单独验证 Loki：

```bash
docker run -d --name aap-loki --network aap-obs --network-alias loki \
  -p 127.0.0.1:3100:3100 \
  -v "$PWD/deploy/observability/loki.yml:/etc/loki/loki.yml:ro" \
  -v aap-loki-data:/loki grafana/loki:3.2.1 \
  -config.file=/etc/loki/loki.yml
```

Promtail 需要只读挂载 Docker socket：

```bash
docker run -d --name aap-promtail --network aap-obs \
  -v "$PWD/deploy/observability/promtail.yml:/etc/promtail/promtail.yml:ro" \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -v aap-promtail-positions:/promtail grafana/promtail:3.2.1 \
  -config.file=/etc/promtail/promtail.yml
```

Grafana 启动时自动加载 `Loki` 数据源和 `AI Agent Platform - Logs` 看板；已运行的 Grafana 需要重启一次以触发 provisioning。

## 验证

```bash
curl -s http://127.0.0.1:3100/ready
# ready

curl -sG --data-urlencode 'query={container="aap-grafana"}' \
  --data-urlencode "start=$(( $(date +%s) - 600 ))000000000" \
  --data-urlencode 'limit=5' \
  http://127.0.0.1:3100/loki/api/v1/query_range
```

2026-10-29 本机实测：Loki `/ready` 返回 `ready`，Promtail 发现 Docker 容器，查询到 `aap-grafana` 的真实 stdout 日志（`logger=cleanup ...`）。

## 注意

- Promtail 读取 Docker socket，权限等同于宿主机 Docker；仅限本地/受控环境。
- Loki filesystem 存储适合本地和单机演示；生产应使用对象存储（S3/MinIO）和独立保留策略。
- 原生启动的 backend（非容器）不会被 Docker SD 采集；Compose 部署的 `backend` 容器会自动进入 Loki。
- 默认不把日志内容发送到外部服务。
