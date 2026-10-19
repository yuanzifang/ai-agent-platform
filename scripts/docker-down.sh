#!/bin/bash
# 停止 Docker Compose 环境；默认保留数据卷
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ ! -f .env.docker ]; then
  echo "未找到 .env.docker，无法确定 Compose 环境变量"
  exit 1
fi
if [ "${1:-}" = "--volumes" ]; then
  echo "⚠ 将删除 MySQL/Redis/PgVector/MinIO/Ollama 数据卷，且不可恢复。"
  read -r -p "确认输入 DELETE 继续：" answer
  [ "$answer" = "DELETE" ] || { echo "已取消"; exit 1; }
  docker compose --env-file .env.docker down --volumes
else
  docker compose --env-file .env.docker down
fi
