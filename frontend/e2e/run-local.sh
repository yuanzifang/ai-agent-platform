#!/usr/bin/env bash
# 本地 E2E 自包含准备：把内置 DeepSeek Provider 临时指向离线 mock，放宽租户限流，
# 测试结束后恢复 Provider。数据库默认按本地开发环境 root/root；可用 MYSQL_* 覆盖。
set -euo pipefail

cd "$(dirname "$0")/.."

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-ai_agent_platform}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
REDIS_DATABASE="${REDIS_DATABASE:-0}"

mysql_args=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USERNAME")
if [[ -n "$MYSQL_PASSWORD" ]]; then
  mysql_args+=("-p$MYSQL_PASSWORD")
fi

mysql_exec() {
  mysql "${mysql_args[@]}" "$MYSQL_DATABASE" -N -s -e "$1"
}

if ! mysql_exec "SELECT 1" >/dev/null 2>&1; then
  echo "无法连接 MySQL（${MYSQL_USERNAME}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}）。" >&2
  echo "可设置 MYSQL_HOST/MYSQL_PORT/MYSQL_USERNAME/MYSQL_PASSWORD/MYSQL_DATABASE 覆盖。" >&2
  exit 1
fi

provider_row="$(mysql_exec "SELECT id, base_url FROM provider WHERE provider_type='DEEPSEEK' ORDER BY id LIMIT 1")"
if [[ -z "$provider_row" ]]; then
  echo "未找到 DEEPSEEK Provider，无法为 E2E 指向离线 mock。" >&2
  exit 1
fi
provider_id="${provider_row%%$'\t'*}"
original_base_url="${provider_row#*$'\t'}"
original_base_url="${original_base_url//\'/\'\'}"

restore_provider() {
  mysql_exec "UPDATE provider SET base_url='${original_base_url}' WHERE id=${provider_id}" >/dev/null 2>&1 || true
  echo "已恢复 DEEPSEEK Provider(${provider_id}) base_url=${original_base_url}"
}
trap restore_provider EXIT INT TERM

mysql_exec "UPDATE provider SET base_url='http://127.0.0.1:18080' WHERE id=${provider_id}" >/dev/null
mysql_exec "UPDATE sys_tenant_quota SET max_api_calls_per_min=100000" >/dev/null

# 配额在 Redis 缓存 5 分钟，改库后必须清掉，否则本地仍按旧的 60/分钟限流。
if command -v redis-cli >/dev/null 2>&1; then
  redis_args=(-h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DATABASE")
  if [[ -n "$REDIS_PASSWORD" ]]; then
    redis_args+=(-a "$REDIS_PASSWORD" --no-auth-warning)
  fi
  for key in $(redis-cli "${redis_args[@]}" --scan --pattern 'quota:apilimit:*' 2>/dev/null); do
    redis-cli "${redis_args[@]}" DEL "$key" >/dev/null
  done
else
  echo "警告：未找到 redis-cli，无法清除租户限流缓存；E2E 可能因旧的 60/分钟限流失败。" >&2
fi

if ! curl -fsS -m 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
  echo "后端未就绪：请先在 backend 启动 Spring Boot（http://127.0.0.1:8080）。" >&2
  exit 1
fi

# webServer 会自动拉起 e2e/mock_servers.py 与 Vite；已有进程时复用。
./node_modules/.bin/playwright test "$@"
