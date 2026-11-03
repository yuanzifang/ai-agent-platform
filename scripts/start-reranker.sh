#!/usr/bin/env bash
# 启动本地 BGE reranker（方案 B）。默认监听 0.0.0.0:8010，供 k3d 内后端访问。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="${RERANKER_VENV:-$HOME/.cache/agri-reranker-venv}"
HF_HOME="${RERANKER_HF_HOME:-$HOME/.cache/agri-reranker-hf}"
MODEL="${RERANKER_MODEL:-BAAI/bge-reranker-v2-m3}"
PORT="${RERANKER_PORT:-8010}"

if [[ ! -x "$VENV/bin/python" ]]; then
  /opt/homebrew/bin/uv venv --python 3.11 "$VENV"
  /opt/homebrew/bin/uv pip install --python "$VENV/bin/python" "sentence-transformers>=3.0"
fi

mkdir -p "$HF_HOME"
exec env \
  PYTHONUNBUFFERED=1 \
  HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}" \
  HF_HUB_DISABLE_XET=1 \
  HF_HOME="$HF_HOME" \
  "$VENV/bin/python" "$ROOT/scripts/reranker_server.py" \
  --host 0.0.0.0 --port "$PORT" --model "$MODEL"
