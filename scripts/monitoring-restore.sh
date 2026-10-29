#!/usr/bin/env bash
# 监控数据恢复。危险操作；必须显式传 --confirm。
# 用法：MONITORING_BACKUP_DIR=backups/monitoring/... scripts/monitoring-restore.sh --confirm
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

if [ "${1:-}" != "--confirm" ]; then
  echo "拒绝执行：请加 --confirm；恢复会覆盖 Loki/Tempo 对象和 Grafana/Prometheus 本地数据。" >&2
  exit 2
fi
DEST="${MONITORING_BACKUP_DIR:?MONITORING_BACKUP_DIR required}"
[ -d "$DEST" ] || { echo "备份目录不存在：$DEST" >&2; exit 1; }
if [ -f "$DEST/SHA256SUMS" ]; then
  ( cd "$DEST" && shasum -a 256 -c SHA256SUMS )
fi

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD required}"
mc alias set aap-restore "${MINIO_ENDPOINT:-http://127.0.0.1:9000}" \
  "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

echo "→ 恢复 Loki/Tempo S3"
[ -d "$DEST/loki-chunks" ] && mc mirror --overwrite "$DEST/loki-chunks" aap-restore/loki-chunks
[ -d "$DEST/tempo-traces" ] && mc mirror --overwrite "$DEST/tempo-traces" aap-restore/tempo-traces

if [ -f "$DEST/grafana.db" ] && docker ps --format '{{.Names}}' | grep -qx 'aap-grafana'; then
  echo "→ 恢复 Grafana SQLite（停止→替换→启动）"
  docker stop aap-grafana >/dev/null
  docker cp "$DEST/grafana.db" aap-grafana:/var/lib/grafana/grafana.db
  docker start aap-grafana >/dev/null
fi

if [ -f "$DEST/prometheus-tsdb.tar.gz" ] && docker ps --format '{{.Names}}' | grep -qx 'aap-prometheus'; then
  echo "→ 恢复 Prometheus TSDB（停止→解包→启动）"
  SRC=$(docker inspect aap-prometheus --format '{{range .Mounts}}{{if eq .Destination "/prometheus"}}{{.Source}}{{end}}{{end}}')
  ABS_DEST=$(cd "$DEST" && pwd)
  if [ -z "$SRC" ]; then echo "找不到 Prometheus /prometheus 挂载源" >&2; exit 1; fi
  docker stop aap-prometheus >/dev/null
  docker run --rm -v "$SRC":/prometheus -v "$ABS_DEST":/backup busybox:1.36 \
    sh -c 'rm -rf /prometheus/* && tar -xzf /backup/prometheus-tsdb.tar.gz -C /prometheus'
  docker start aap-prometheus >/dev/null
fi

echo "✔ 监控恢复完成"
