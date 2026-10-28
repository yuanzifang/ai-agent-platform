#!/usr/bin/env bash
# Qdrant 本地按需启停；默认不常驻。
set -euo pipefail
ACTION="${1:-start}"
NAME=aap-qdrant-local
VOLUME=aap-qdrant-data
IMAGE=qdrant/qdrant:latest

case "$ACTION" in
  start)
    docker volume create "$VOLUME" >/dev/null
    if docker inspect "$NAME" >/dev/null 2>&1; then
      docker start "$NAME" >/dev/null
    else
      docker run -d --name "$NAME" --memory=256m -p 6333:6333 \
        -v "$VOLUME":/qdrant/storage "$IMAGE" >/dev/null
    fi
    for _ in $(seq 1 30); do
      code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:6333/collections 2>/dev/null || true)
      [ "$code" = "200" ] && break
      sleep 1
    done
    echo "Qdrant: http://127.0.0.1:6333"
    echo "在平台基础设施页启用 Qdrant 并重启后端后，知识库才可选择 QDRANT。"
    ;;
  stop)
    docker stop "$NAME" >/dev/null 2>&1 || true
    docker rm "$NAME" >/dev/null 2>&1 || true
    echo "Qdrant 已停止并移除容器；数据卷 $VOLUME 保留。"
    ;;
  *)
    echo "用法: $0 start|stop" >&2
    exit 2
    ;;
esac
