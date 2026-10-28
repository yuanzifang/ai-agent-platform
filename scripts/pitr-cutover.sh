#!/usr/bin/env bash
# 冷切流演练：停写 → 克隆源库到临时库 → 8080 切到临时库 → smoke → 回切原库。
# 默认 dry-run；必须 --apply --yes 才执行。原库只读，不 DROP/不清洗。
#
# 用法：
#   ./scripts/pitr-cutover.sh --jar /path/backend.jar --dry-run
#   ./scripts/pitr-cutover.sh --jar /path/backend.jar --apply --yes
#   ./scripts/pitr-cutover.sh --jar /path/backend.jar --apply --yes --no-rollback
set -Eeuo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

APPLY=0
ASSUME_YES=0
NO_ROLLBACK=0
KEEP_TARGET=0
ALLOW_NO_APP=0
PORT=8080
JAR="${APP_JAR:-$ROOT/backend/target/ai-agent-platform-backend-0.1.0-SNAPSHOT.jar}"
TARGET_DB=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --yes) ASSUME_YES=1 ;;
    --no-rollback) NO_ROLLBACK=1 ;;
    --keep-target) KEEP_TARGET=1 ;;
    --allow-no-app) ALLOW_NO_APP=1 ;;
    --port) PORT="${2:?--port 需要端口}"; shift ;;
    --jar) JAR="${2:?--jar 需要 jar}"; shift ;;
    --target-db) TARGET_DB="${2:?--target-db 需要库名}"; shift ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
schema_signature() {
  local db="$1"
  "${MYSQL[@]}" -N -B -e "SELECT CONCAT(
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_type='BASE TABLE'), ':',
    COALESCE((SELECT MAX(installed_rank) FROM \`${db}\`.flyway_schema_history), 0), ':',
    COALESCE((SELECT version FROM \`${db}\`.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1), ''))
  " 2>/dev/null || true
}

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_BACKUP_USER:-${MYSQL_USERNAME:-root}}"
MYSQL_PASS="${MYSQL_BACKUP_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-root}}}"
SOURCE_DB="${MYSQL_DATABASE:-ai_agent_platform}"
export MYSQL_PWD="$MYSQL_PASS"
TS="$(date +%Y%m%d_%H%M%S)"
TARGET_DB="${TARGET_DB:-${SOURCE_DB}_cutover_${TS}}"
if [ "$NO_ROLLBACK" = "1" ]; then KEEP_TARGET=1; fi
[[ "$TARGET_DB" =~ ^[A-Za-z0-9_]+$ ]] || die "非法目标库名：$TARGET_DB"
case "$TARGET_DB" in
  *_cutover_*|*_pitr_*|*_drill_*) ;;
  *) die "目标库必须包含 _cutover_ / _pitr_ / _drill_ 前缀，拒绝：$TARGET_DB" ;;
esac
[ "$TARGET_DB" != "$SOURCE_DB" ] || die "目标库不能等于源库"
[ -f "$JAR" ] || die "后端 jar 不存在：$JAR"
command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
command -v mysqldump >/dev/null 2>&1 || die "缺少 mysqldump"
command -v lsof >/dev/null 2>&1 || die "缺少 lsof"
MYSQL=(mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER")
MYSQLDUMP=(mysqldump --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER"
  --single-transaction --quick --skip-lock-tables --no-tablespaces
  --routines --triggers --events --hex-blob --default-character-set=utf8mb4
  --set-gtid-purged=OFF --column-statistics=0)
"${MYSQL[@]}" -e "SELECT 1" >/dev/null 2>&1 || die "MySQL 连接失败"

APP_PID="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN | head -1 || true)"
log "切流目标：source=${SOURCE_DB} target=${TARGET_DB} port=${PORT}"
log "APP_PID=${APP_PID:-none} JAR=$JAR"
if [ "$APPLY" != "1" ]; then
  log "dry-run：未停止应用、未写库；加 --apply --yes 执行"
  exit 0
fi
[ "$ASSUME_YES" = "1" ] || { printf '将停写并切换 8080 到 %s，输入 yes 继续：' "$TARGET_DB"; read -r a; [ "$a" = "yes" ] || die "已取消"; }

TARGET_STARTED=0
SOURCE_STOPPED=0
TARGET_PID=""
CUTOVER_START=$(now_ms)
BACKUP_TMP="$(mktemp "${TMPDIR:-/tmp}/aap-cutover.XXXXXX.sql.gz")"

start_app() {
  local db="$1" logfile="$2"
  MYSQL_DATABASE="$db" OTEL_ENABLED=false \
    java -Xmx3g -jar "$JAR" --server.port="$PORT" > "$logfile" 2>&1 &
  TARGET_PID=$!
  for _ in $(seq 1 150); do
    kill -0 "$TARGET_PID" 2>/dev/null || return 1
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/health" 2>/dev/null || true)
    if [ "$code" = "200" ]; then
      local owner
      owner="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN | head -1 || true)"
      [ "$owner" = "$TARGET_PID" ] || return 1
      return 0
    fi
    sleep 1
  done
  return 1
}

smoke() {
  local db="$1"
  local token health audit agents
  health=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/health" 2>/dev/null || true)
  [ "$health" = "200" ] || die "health=${health}"
  token=$(curl -s -X POST "http://127.0.0.1:${PORT}/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"dev","password":"Dev@123456","tenantKey":"dev"}' \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
  [ -n "$token" ] || die "登录失败"
  audit=$(curl -s "http://127.0.0.1:${PORT}/api/v1/audit/verify" -H "Authorization: Bearer $token" \
    | python3 -c 'import sys,json; print((json.load(sys.stdin).get("data") or {}).get("valid"))')
  agents=$(curl -s "http://127.0.0.1:${PORT}/api/v1/agent?page=1&pageSize=1" -H "Authorization: Bearer $token" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("code")==0 and isinstance((d.get("data") or {}).get("list"),list))')
  [ "$audit" = "True" ] || die "${db} 审计链校验失败：${audit}"
  [ "$agents" = "True" ] || die "${db} Agent 列表不可读"
  log "smoke PASS db=${db} health=200 audit=true agents=true"
}

cleanup() {
  local status=$?
  set +e
  if [ "$NO_ROLLBACK" != "1" ] && [ "$SOURCE_STOPPED" = "1" ]; then
    log "清理/自动回切：停止目标实例，恢复源库实例"
    if [ -n "$TARGET_PID" ] && kill -0 "$TARGET_PID" 2>/dev/null; then
      kill "$TARGET_PID" 2>/dev/null
      for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$TARGET_PID" 2>/dev/null || break; sleep 1; done
      kill -9 "$TARGET_PID" 2>/dev/null
    fi
    if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
      start_app "$SOURCE_DB" /private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/backend_cutover_rollback.log \
        && smoke "$SOURCE_DB" || log "ERROR: 原库实例恢复失败，请检查日志"
    fi
  elif [ "$NO_ROLLBACK" = "1" ] && [ -n "$TARGET_PID" ] && kill -0 "$TARGET_PID" 2>/dev/null; then
    log "保留切流：target=${TARGET_DB} 实例仍在 ${PORT} 运行"
  fi
  if [ "$KEEP_TARGET" != "1" ]; then
    "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`" >/dev/null 2>&1
    log "已删除临时库：${TARGET_DB}"
  else
    log "保留临时库：${TARGET_DB}"
  fi
  rm -f "$BACKUP_TMP"
  exit "$status"
}
trap cleanup EXIT

if [ -n "$APP_PID" ]; then
  log "停止应用 PID=${APP_PID}（停写）"
  kill "$APP_PID"
  for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
  kill -9 "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 10); do lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 1; done
  lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 && die "端口 ${PORT} 未释放，拒绝继续切流"
  SOURCE_STOPPED=1
else
  [ "$ALLOW_NO_APP" = "1" ] || die "8080 未运行；为避免并发写入，必须显式 --allow-no-app"
  log "未发现 ${PORT} 端口应用，按 --allow-no-app 继续"
  SOURCE_STOPPED=1
fi

SOURCE_SIG_BEFORE="$(schema_signature "$SOURCE_DB")"
log "克隆源库到临时库（冷备份），schema=${SOURCE_SIG_BEFORE}"
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`; CREATE DATABASE \`$TARGET_DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
"${MYSQLDUMP[@]}" "$SOURCE_DB" | gzip -9 > "$BACKUP_TMP"
gzip -t "$BACKUP_TMP" || die "临时 dump 校验失败"
SOURCE_SIG_AFTER="$(schema_signature "$SOURCE_DB")"
[ "$SOURCE_SIG_BEFORE" = "$SOURCE_SIG_AFTER" ] || die "克隆期间源库 schema 发生变化（${SOURCE_SIG_BEFORE} -> ${SOURCE_SIG_AFTER}），拒绝切流；请停掉其他后端后重试"
gunzip -c "$BACKUP_TMP" | "${MYSQL[@]}" "$TARGET_DB"
SOURCE_TABLES="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${SOURCE_DB}' AND table_type='BASE TABLE'")"
TARGET_TABLES="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_type='BASE TABLE'")"
[ "$SOURCE_TABLES" = "$TARGET_TABLES" ] || die "表数不一致：source=${SOURCE_TABLES} target=${TARGET_TABLES}"
log "冷克隆完成：${SOURCE_TABLES} 张表"

log "启动临时库实例"
start_app "$TARGET_DB" /private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/backend_cutover_target.log \
  || { tail -80 /private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/backend_cutover_target.log; die "临时库实例启动失败"; }
TARGET_STARTED=1
smoke "$TARGET_DB"
CUTOVER_END=$(now_ms)
log "切流完成：target=${TARGET_DB}，耗时约 $(( (CUTOVER_END-CUTOVER_START)/1000 ))s"

if [ "$NO_ROLLBACK" = "1" ]; then
  log "已按 --no-rollback 保留切流；原库未修改，回滚=停止 target、启动 source"
  exit 0
fi

log "执行回切（验证 rollback）"
if [ -n "$TARGET_PID" ] && kill -0 "$TARGET_PID" 2>/dev/null; then
  kill "$TARGET_PID" 2>/dev/null
  for _ in $(seq 1 20); do kill -0 "$TARGET_PID" 2>/dev/null || break; sleep 1; done
  kill -9 "$TARGET_PID" 2>/dev/null || true
fi
TARGET_PID=""
start_app "$SOURCE_DB" /private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/backend_cutover_rollback.log \
  || { tail -80 /private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/backend_cutover_rollback.log; die "回切原库失败"; }
SOURCE_STOPPED=0
smoke "$SOURCE_DB"
log "回切完成：source=${SOURCE_DB} 健康，原库数据未修改；临时库将清理"
