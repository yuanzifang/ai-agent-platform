#!/usr/bin/env bash
# 本机 API 基线（非验收压测）：需要后端已启动、ab 可用、租户限流足够高。
set -euo pipefail

BASE_URL="${PERF_BASE_URL:-http://localhost:8080}"
USERNAME="${PERF_USERNAME:-dev}"
PASSWORD="${PERF_PASSWORD:-Dev@123456}"
TENANT="${PERF_TENANT:-dev}"
REQUESTS="${PERF_REQUESTS:-200}"
CONCURRENCY="${PERF_CONCURRENCY:-20}"
AB="${AB_BIN:-/usr/sbin/ab}"

TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"tenantKey\":\"$TENANT\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')

if [ -z "$TOKEN" ]; then
  echo "login failed" >&2
  exit 1
fi

echo "| endpoint | qps | mean ms | p50 ms | p95 ms | p99 ms | failed |"
echo "|---|---:|---:|---:|---:|---:|---:|"
for endpoint in \
  "/api/v1/agent" \
  "/api/v1/knowledge-base?page=1&pageSize=20" \
  "/api/v1/observability/overview?period=24h" \
  "/api/v1/cases?page=1&pageSize=20"
do
  out=$("$AB" -n "$REQUESTS" -c "$CONCURRENCY" \
    -H "Authorization: Bearer $TOKEN" -H 'Accept-Language: zh-CN' \
    "$BASE_URL$endpoint" 2>/dev/null)
  qps=$(printf '%s\n' "$out" | awk '/Requests per second/{print $4; exit}')
  mean=$(printf '%s\n' "$out" | awk '/Time per request/{print $4; exit}')
  p50=$(printf '%s\n' "$out" | awk '$1=="50%"{print $2; exit}')
  p95=$(printf '%s\n' "$out" | awk '$1=="95%"{print $2; exit}')
  p99=$(printf '%s\n' "$out" | awk '$1=="99%"{print $2; exit}')
  failed=$(printf '%s\n' "$out" | awk '/Failed requests/{print $3; exit}')
  echo "| $endpoint | $qps | $mean | $p50 | $p95 | $p99 | $failed |"
done
