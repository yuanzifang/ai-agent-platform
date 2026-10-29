#!/usr/bin/env bash
# 监控数据备份：Prometheus TSDB、Grafana SQLite、Loki/Tempo S3 对象。
# 默认只读备份，不停止服务；S3 数据使用 mc mirror，Prometheus 使用容器内快照 tar。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

STAMP="${MONITORING_BACKUP_STAMP:-$(date +%Y%m%d-%H%M%S)}"
DEST="${MONITORING_BACKUP_DIR:-backups/monitoring/$STAMP}"
mkdir -p "$DEST"

if ! command -v mc >/dev/null 2>&1; then
  echo "需要 MinIO 客户端 mc" >&2
  exit 1
fi
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD required}"
MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:9000}"
mc alias set aap-backup "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

echo "→ Prometheus TSDB"
if docker ps --format '{{.Names}}' | grep -qx 'aap-prometheus'; then
  docker exec aap-prometheus tar -czf /tmp/prometheus-tsdb.tar.gz -C /prometheus .
  docker cp aap-prometheus:/tmp/prometheus-tsdb.tar.gz "$DEST/prometheus-tsdb.tar.gz"
  docker exec aap-prometheus rm -f /tmp/prometheus-tsdb.tar.gz
else
  echo "  skip: aap-prometheus 未运行"
fi

echo "→ Grafana SQLite"
if docker ps --format '{{.Names}}' | grep -qx 'aap-grafana'; then
  docker cp aap-grafana:/var/lib/grafana/grafana.db "$DEST/grafana.db"
else
  echo "  skip: aap-grafana 未运行"
fi

echo "→ Alertmanager 数据"
if docker ps --format '{{.Names}}' | grep -qx 'aap-alertmanager-local'; then
  docker cp aap-alertmanager-local:/alertmanager "$DEST/alertmanager"
elif docker ps --format '{{.Names}}' | grep -qx 'aap-alertmanager'; then
  docker cp aap-alertmanager:/alertmanager "$DEST/alertmanager"
else
  echo "  skip: Alertmanager 未运行"
fi

echo "→ Loki S3"
mc mirror --overwrite --quiet aap-backup/loki-chunks "$DEST/loki-chunks"
echo "→ Tempo S3"
mc mirror --overwrite --quiet aap-backup/tempo-traces "$DEST/tempo-traces"

cat > "$DEST/MANIFEST" <<EOF
created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source_host=$(hostname)
stamp=$STAMP
EOF
( cd "$DEST" && find . -type f ! -name SHA256SUMS -print0 | xargs -0 shasum -a 256 > SHA256SUMS )

echo "✔ 监控备份完成：$DEST"
