#!/usr/bin/env bash
# 初始化监控对象存储 bucket（幂等），并开启版本化。
# 不设置对象过期：Loki/Tempo 自己管理保留；对象生命周期错误会导致查询缺块。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD required}"
command -v mc >/dev/null || { echo "需要 mc" >&2; exit 1; }
mc alias set aap-s3 "${MINIO_ENDPOINT:-http://127.0.0.1:9000}" \
  "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
for b in loki-chunks tempo-traces; do
  mc mb --ignore-existing "aap-s3/$b" >/dev/null
  mc version enable "aap-s3/$b" >/dev/null
  echo "✔ $b"
done
