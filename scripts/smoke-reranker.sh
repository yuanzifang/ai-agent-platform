#!/usr/bin/env bash
# 本地 reranker 冒烟测试：健康检查 + 标准 /rerank 协议。
set -euo pipefail
URL="${RERANKER_URL:-http://127.0.0.1:8010}"
curl -fsS "$URL/health" >/dev/null
curl -fsS -X POST "$URL/rerank" \
  -H 'Content-Type: application/json' \
  -d '{"model":"bge-reranker-v2-m3","query":"水稻稻瘟病怎么防治","documents":["花生叶斑病防治","水稻稻瘟病破口期用药","玉米螟防治"],"top_n":3}' \
  | python3 -m json.tool
