# AI Agent Platform 监控、告警与定时备份

本目录是 UTM Linux 宿主机上的 Docker Compose 监控栈，不部署业务应用。业务仍在 K8s 中运行，监控栈通过 NodePort + k3d 宿主机映射采集。

## 组件与端口

| 组件 | 宿主机端口 | 说明 |
| --- | ---: | --- |
| Prometheus | 9090 | 指标采集、告警规则 |
| Grafana | 3000 | 看板，默认用户 `admin` |
| Alertmanager | 9093 | 告警路由 |
| alert-relay | 18099 | Alertmanager → 飞书/钉钉 relay |
| node-exporter | 19100 | UTM 宿主机 CPU/内存/磁盘 |
| mysqld-exporter | 19104 | MySQL 存活、连接、慢查询 |
| redis-exporter | 19121 | Redis 存活、内存、连接 |
| postgres-exporter | 19187 | PostgreSQL 存活、连接 |
| Prometheus 抓取 backend | K8s API pod proxy | 每个 backend Pod 的 `/actuator/prometheus`，用于独立 JVM 指标 |
| Prometheus 抓取 cAdvisor | K8s 节点 10250 | `/metrics/cadvisor`，容器 CPU/内存/OOM |

K8s 只新增以下 NodePort，k3d 只把宿主机端口绑定到 `127.0.0.1`：

- backend metrics：`30081` → 宿主机 `18081`（保留用于手工检查，Prometheus 已改为按 Pod 抓取）
- MySQL：`30306` → 宿主机 `13306`
- PostgreSQL：`30543` → 宿主机 `15432`
- Redis：`30637` → 宿主机 `16379`
- MinIO API：`30900` → 宿主机 `19000`

## 启动

在 UTM 监控目录（例如 `/home/fangyuan/aap-monitoring`）：

```bash
cp .env.example .env
cp alert.env.example alert.env
chmod 600 .env alert.env
# .env 填数据库密码和 Grafana 密码；alert.env 填告警 webhook / SMTP / Redis 密码
docker compose config -q
docker compose up -d
docker compose ps
```

`alert-relay/` 需要从仓库 `runtime/alert-relay/` 复制，包含 `node_modules`；或者按 `runtime/alert-relay/Dockerfile` 构建镜像后替换 compose 中的 `image`。

`alert.env` 只包含告警 relay 需要的 `ALERT_*`、`SMTP_*`、`REDIS_PASSWORD` 等变量，不要使用完整业务 `.env`，减少凭据暴露面。

## 指标来源

- **backend QPS/延迟/5xx**：Spring Actuator `/actuator/prometheus`。
- **backend CPU/内存**：cAdvisor 按 Pod/container 统计；JVM、进程 CPU、QPS 通过 K8s API pod proxy 按 Pod 采集。
- **MySQL 慢 SQL**：`mysql_global_status_slow_queries`；看板显示 5 分钟速率，告警阈值 > 0.1/s。
- **数据库 CPU/内存**：cAdvisor 按 namespace/pod/container 统计 MySQL/PG/Redis/ES/MinIO/Qdrant；host 层另有 node-exporter。
- **数据库存活**：`mysql_up`、`pg_up`、`redis_up`。
- **OOM**：cAdvisor `container_oom_events_total` 增量告警。

## Grafana

自动加载四个看板：

- Overview：`/d/aap-observability`
- Containers：`/d/aap-containers`，支持按 Namespace / Pod / Container 选择
- JVM / Gateway：`/d/aap-jvm`，支持按 backend Pod 选择
- Slow SQL：`/d/aap-slow-sql`，显示时间、耗时、扫描行和 SQL 文本

数据源：

- Prometheus：`http://127.0.0.1:9090`
- MySQL Slow Log：`127.0.0.1:13306`，只读用户 `grafana_monitor`，密码来自 `GRAFANA_MYSQL_PASSWORD`

密码从 `.env` 的 `GRAFANA_ADMIN_PASSWORD` / `GRAFANA_MYSQL_PASSWORD` 读取，不写入文档或日志。

当前 K8s 没有独立的 `agent-gateway` Deployment；Spring backend 同时承担平台网关，所以 JVM 看板显示的是 backend Pod。frontend 和 agent runtime 使用 nginx，没有 JVM 指标。
JVM 看板的 Young GC / Old GC / Full GC 面板基于 Micrometer `jvm_gc_pause_seconds_*`，属于事件驱动指标：JVM 启动后尚未发生对应 GC 时没有序列，面板会显示无数据；发生 GC 后自动出现。若需要确认实际标签，可查看 `GC Count by Action / Cause` 面板，或直接查询 `jvm_gc_pause_seconds_count{job="ai-agent-backend-pods"}`。


## 告警推送

`alertmanager.yml` 将 critical/warning/default 路由到 `alert-relay`，再由 relay 使用 `.env` 中的 `ALERT_WEBHOOK_URL` 推送到飞书或钉钉。

验证：

```bash
curl -X POST http://127.0.0.1:9093/api/v2/alerts \
  -H 'Content-Type: application/json' \
  --data '[{"labels":{"alertname":"AapAlertRelayTest","severity":"critical","instance":"test"},"annotations":{"summary":"告警链路测试"}}]'
```

Alertmanager 指标 `alertmanager_notifications_failed_total{integration="webhook"}` 应为 0；relay 日志应出现 `alertmanager webhook received`。

## cAdvisor / kubelet token

`kubelet-rbac.yaml` 和 `k8s-prometheus-rbac.yaml` 创建 ServiceAccount `aap-monitoring/aap-prometheus`，绑定 kubelet 指标权限和 Pod/Proxy 读取权限，用于 cAdvisor 与按 Pod JVM 抓取。在 UTM 监控目录生成 token：

```bash
kubectl -n aap-monitoring create token aap-prometheus --duration=8760h \
  | sudo tee /home/fangyuan/aap-monitoring/kubelet-token >/dev/null
sudo chown 65534:65534 /home/fangyuan/aap-monitoring/kubelet-token
sudo chmod 600 /home/fangyuan/aap-monitoring/kubelet-token
docker compose restart prometheus
```

Prometheus 以非 root 用户运行，token 文件必须能被 uid 65534 读取；部署脚本使用 `chown 65534:65534`。token 默认一年有效，到期前重新生成并重启 Prometheus。cAdvisor 目标使用 k3d 节点 IP（当前 `172.20.0.2/3/4`）；k3d 网络重建后需要更新 `prometheus.yml` 中 `kubelet-cadvisor` 的 targets。

## 定时备份

Compose 中四个常驻备份容器每天执行一次，写入宿主机 `backups/`，并清理 14 天前文件：

- `backup-mysql`：`mysqldump --single-transaction`，文件名 `mysql-YYYYmmdd-HHMMSS.sql.gz`
- `backup-pg`：`pg_dump vector`，文件名 `pg-vector-YYYYmmdd-HHMMSS.sql.gz`
- `backup-redis`：`redis-cli --rdb`，文件名 `redis-YYYYmmdd-HHMMSS.rdb`
- `backup-minio`：`mc mirror`，目录 `minio-YYYYmmdd-HHMMSS/`

检查：

```bash
find backups -maxdepth 1 -type f -ls
gzip -t backups/mysql-*.gz
docker run --rm --entrypoint redis-check-rdb \
  -v "$PWD/backups:/backup" redis:8.10-alpine /backup/redis-*.rdb
```

备份容器首次启动会立即执行一次，之后每 24 小时一次。当前备份保存在 UTM 本机，尚未配置异地/对象存储；生产环境应把 `backups/` 同步到独立存储。
## MySQL 慢 SQL 账号

Grafana 使用只读账号 `grafana_monitor`。首次部署时用 root 执行（密码用环境变量或手工替换，不写入仓库）：

```sql
CREATE USER 'grafana_monitor'@'%' IDENTIFIED BY '<password>';
GRANT SELECT ON mysql.slow_log TO 'grafana_monitor'@'%';
```

## 已知运维风险

- K8s 依赖 k3d 的 serverlb 提供 6443/18080/NodePort 映射；serverlb 异常退出时，应用和监控会同时失去 NodePort 入口。本次部署期间出现过一次 serverlb Exit 137，已用 `k3d cluster stop/start aap-k8s` 恢复。生产环境建议增加 k3d/Docker 健康检查和自动恢复。
- cAdvisor 使用 3 个 k3d 节点当前 IP；节点容器重启后 IP 或角色可能变化，需要重新确认 `prometheus.yml` 的 `kubelet-cadvisor` targets。
## 查看慢 SQL

Grafana 的 **Slow SQL** 看板通过 MySQL 数据源读取 `mysql.slow_log`，可显示时间、Query_time、Lock_time、扫描行和完整 SQL。Prometheus 仍提供慢查询数量/速率。当前 MySQL 已开启：

```text
slow_query_log=ON
long_query_time=1s
log_output=FILE,TABLE
slow_query_log_file=/var/lib/mysql/mysql-0-slow.log
```

查看具体 SQL：

```bash
kubectl -n aap-data exec mysql-0 -- tail -100 /var/lib/mysql/mysql-0-slow.log
kubectl -n aap-data exec -it mysql-0 -- tail -f /var/lib/mysql/mysql-0-slow.log
```

文件日志同样包含 `Query_time`、`Lock_time`、`Rows_examined` 和 SQL。`mysql.slow_log` 是 log table，备份容器每天用 `TRUNCATE TABLE` 清理，保留当天表数据；完整历史仍看文件日志。调整阈值：

```bash
kubectl -n aap-data exec mysql-0 -- sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SET PERSIST long_query_time=2;"'
```

Prometheus 查询示例：

```text
rate(mysql_global_status_slow_queries[5m])       # 每秒慢查询数
increase(mysql_global_status_slow_queries[1h])   # 近 1 小时慢查询数
```
