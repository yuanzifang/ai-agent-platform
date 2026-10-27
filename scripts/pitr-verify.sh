#!/usr/bin/env bash
# PITR 恢复后的业务级只读校验：核心表、Flyway、审计链、引用完整性、可选 API smoke。
# 不写入、不删除目标库；可以安全地对生产只读副本/恢复库执行。
#
# 用法：
#   ./scripts/pitr-verify.sh --target-db ai_agent_platform_pitr_test
#   ./scripts/pitr-verify.sh --target-db ai_agent_platform_pitr_test \
#     --backup backups/latest --require-business-data
#   PITR_API_TOKEN=... ./scripts/pitr-verify.sh --target-db ai_agent_platform \
#     --api-base http://127.0.0.1:8080 --require-business-data
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

TARGET_DB=""
BACKUP_ARG=""
API_BASE="${PITR_API_BASE:-}"
TOKEN="${PITR_API_TOKEN:-}"
REQUIRE_BUSINESS=0
STRICT_REFS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --target-db) TARGET_DB="${2:?--target-db 需要库名}"; shift ;;
    --backup) BACKUP_ARG="${2:?--backup 需要目录}"; shift ;;
    --api-base) API_BASE="${2:?--api-base 需要 URL}"; shift ;;
    --token) TOKEN="${2:?--token 需要 token}"; shift ;;
    --require-business-data) REQUIRE_BUSINESS=1 ;;
    --strict-refs) STRICT_REFS=1 ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

[ -n "$TARGET_DB" ] || { echo "必须显式指定 --target-db（避免误判生产库）" >&2; exit 2; }
[[ "$TARGET_DB" =~ ^[A-Za-z0-9_]+$ ]] || { echo "非法库名：$TARGET_DB" >&2; exit 2; }

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
ok() { log "PASS  $*"; }
warn() { log "WARN  $*"; }
bad() { log "FAIL  $*"; FAILED=$((FAILED + 1)); }
die() { log "ERROR: $*" >&2; exit 2; }

FAILED=0
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/aiagent-pitr-verify.XXXXXX")"
CORE_BASELINE="$TMP_DIR/core-counts.tsv"
: > "$CORE_BASELINE"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_BACKUP_USER="${MYSQL_BACKUP_USER:-${MYSQL_USERNAME:-root}}"
MYSQL_BACKUP_PASSWORD="${MYSQL_BACKUP_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-root}}}"
export MYSQL_PWD="$MYSQL_BACKUP_PASSWORD"
MYSQL=(mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_BACKUP_USER")
command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
command -v python3 >/dev/null 2>&1 || die "缺少 python3"
"${MYSQL[@]}" -N -B -e "SELECT 1" >/dev/null 2>&1 || die "MySQL 连接失败：$MYSQL_HOST:$MYSQL_PORT"

q() { "${MYSQL[@]}" -N -B -e "$1"; }
table_exists() {
  local n
  n="$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_name='$1'")"
  [ "$n" = "1" ]
}

BASE_TABLES=0
BASE_AUDIT=0
if [ -n "$BACKUP_ARG" ]; then
  case "$BACKUP_ARG" in
    /*) BACKUP="$BACKUP_ARG" ;;
    *) BACKUP="$ROOT/$BACKUP_ARG" ;;
  esac
  [ -f "$BACKUP/manifest.json" ] || die "不是有效备份（缺 manifest.json）：$BACKUP"
  read -r BASE_TABLES BASE_AUDIT < <(python3 - "$BACKUP/manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
mysql = m.get('backends', {}).get('mysql', {})
print(int(mysql.get('tables') or 0), int(mysql.get('auditRows') or 0))
PY
)
  python3 - "$BACKUP/manifest.json" > "$CORE_BASELINE" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
core = m.get('backends', {}).get('mysql', {}).get('coreCounts') or {}
for k, v in core.items():
    try:
        print(f"{k}\t{int(v)}")
    except (TypeError, ValueError):
        pass
PY
  log "基线：backup=$BACKUP tables=$BASE_TABLES audit=$BASE_AUDIT"
fi

log "目标：$TARGET_DB@$MYSQL_HOST:$MYSQL_PORT"

DB_EXISTS="$(q "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${TARGET_DB}'")"
[ "$DB_EXISTS" = "1" ] || { bad "目标库不存在：$TARGET_DB"; log "结果：FAIL（$FAILED 项）"; exit 1; }

TABLE_COUNT="$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_type='BASE TABLE'")"
log "表数量：$TABLE_COUNT"
if [ "$TABLE_COUNT" -le 0 ]; then
  bad "目标库没有业务表"
elif [ "$BASE_TABLES" -gt 0 ] && [ "$TABLE_COUNT" -lt "$BASE_TABLES" ]; then
  bad "表数量少于备份基线：$TABLE_COUNT < $BASE_TABLES"
else
  if [ "$BASE_TABLES" -gt 0 ]; then
    ok "表数量 ≥ 备份基线（${TABLE_COUNT} ≥ ${BASE_TABLES}）"
  else
    ok "表数量 ${TABLE_COUNT}（未提供备份基线）"
  fi
fi

CORE_TABLES=(
  sys_tenant sys_user agent_registry knowledge_base knowledge_document
  conversation message audit_operation billing_usage
  agent_trace agent_span eval_task flyway_schema_history
)
for t in "${CORE_TABLES[@]}"; do
  if table_exists "$t"; then
    ok "表存在：$t"
  else
    bad "核心表缺失：$t"
  fi
done

if table_exists flyway_schema_history; then
  read -r FLYWAY_ROWS FLYWAY_VERSION FLYWAY_FAILED < <(
    q "SELECT COUNT(*), COALESCE(MAX(version),''), COALESCE(SUM(success=0),0)
       FROM \`${TARGET_DB}\`.flyway_schema_history"
  )
  log "Flyway：已应用 ${FLYWAY_ROWS} 条，最新版本 ${FLYWAY_VERSION}，失败记录 ${FLYWAY_FAILED}"
  if [ "$FLYWAY_FAILED" != "0" ]; then
    bad "Flyway 存在失败迁移记录：${FLYWAY_FAILED}"
  elif [ "$FLYWAY_ROWS" -le 0 ]; then
    bad "Flyway 历史为空"
  else
    ok "Flyway 历史可用"
  fi
fi

BASELINE_CORE=0
while IFS=$'\t' read -r bt bc; do
  [ -n "$bt" ] || continue
  BASELINE_CORE=$((BASELINE_CORE + 1))
  if ! table_exists "$bt"; then
    continue
  fi
  actual="$(q "SELECT COUNT(*) FROM \`${TARGET_DB}\`.\`${bt}\`")"
  if [ "$actual" -lt "$bc" ]; then
    bad "业务表行数少于备份基线：$bt $actual < $bc"
  fi
done < "$CORE_BASELINE"
[ "$BASELINE_CORE" -gt 0 ] && log "已比对 $BASELINE_CORE 张业务表基线"

# 审计行数与基线（存在时）
if table_exists audit_operation; then
  AUDIT_COUNT="$(q "SELECT COUNT(*) FROM \`${TARGET_DB}\`.audit_operation")"
  log "审计行数：${AUDIT_COUNT}（基线 ${BASE_AUDIT}）"
  if [ "$BASE_AUDIT" -gt 0 ] && [ "$AUDIT_COUNT" -lt "$BASE_AUDIT" ]; then
    bad "审计行数少于备份基线：$AUDIT_COUNT < $BASE_AUDIT"
  else
    ok "审计行数不低于基线"
  fi
fi

if [ "$REQUIRE_BUSINESS" = "1" ]; then
  for t in sys_tenant agent_registry knowledge_base; do
    if table_exists "$t"; then
      c="$(q "SELECT COUNT(*) FROM \`${TARGET_DB}\`.\`${t}\`")"
      if [ "$c" -le 0 ]; then
        bad "--require-business-data：$t 为空"
      else
        ok "--require-business-data：$t 非空（${c}）"
      fi
    fi
  done
fi

# 引用完整性：核心强约束必须为 0；历史/归档类只告警，--strict-refs 时失败。
check_orphan() {
  local label="$1" sql="$2" mode="${3:-fail}"
  local n
  n="$(q "$sql")" || { bad "$label 检查失败"; return; }
  if [ "$n" = "0" ]; then
    ok "引用完整性：$label = 0"
  elif [ "$mode" = "fail" ] || [ "$STRICT_REFS" = "1" ]; then
    bad "引用完整性：$label = $n"
  else
    warn "引用完整性：$label = ${n}（历史/归档数据，非阻断）"
  fi
}

check_orphan "message -> conversation" \
  "SELECT COUNT(*) FROM \`${TARGET_DB}\`.message m LEFT JOIN \`${TARGET_DB}\`.conversation c ON c.id=m.conversation_id WHERE m.conversation_id IS NOT NULL AND c.id IS NULL"
check_orphan "conversation -> agent" \
  "SELECT COUNT(*) FROM \`${TARGET_DB}\`.conversation c LEFT JOIN \`${TARGET_DB}\`.agent_registry a ON a.id=c.agent_id WHERE c.agent_id IS NOT NULL AND a.id IS NULL"
check_orphan "knowledge_document -> knowledge_base" \
  "SELECT COUNT(*) FROM \`${TARGET_DB}\`.knowledge_document d LEFT JOIN \`${TARGET_DB}\`.knowledge_base b ON b.id=d.knowledge_base_id WHERE d.knowledge_base_id IS NOT NULL AND b.id IS NULL"
if table_exists knowledge_chunk && table_exists knowledge_document; then
  check_orphan "knowledge_chunk -> knowledge_document" \
    "SELECT COUNT(*) FROM \`${TARGET_DB}\`.knowledge_chunk k LEFT JOIN \`${TARGET_DB}\`.knowledge_document d ON d.id=k.document_id WHERE k.document_id IS NOT NULL AND d.id IS NULL" warn
fi
if table_exists agent_knowledge_base; then
  check_orphan "agent_knowledge_base -> agent" \
    "SELECT COUNT(*) FROM \`${TARGET_DB}\`.agent_knowledge_base x LEFT JOIN \`${TARGET_DB}\`.agent_registry a ON a.id=x.agent_id WHERE x.agent_id IS NOT NULL AND a.id IS NULL" warn
  check_orphan "agent_knowledge_base -> knowledge_base" \
    "SELECT COUNT(*) FROM \`${TARGET_DB}\`.agent_knowledge_base x LEFT JOIN \`${TARGET_DB}\`.knowledge_base b ON b.id=x.knowledge_base_id WHERE x.knowledge_base_id IS NOT NULL AND b.id IS NULL" warn
fi
if table_exists agent_span && table_exists agent_trace; then
  check_orphan "agent_span -> agent_trace" \
    "SELECT COUNT(*) FROM \`${TARGET_DB}\`.agent_span s LEFT JOIN \`${TARGET_DB}\`.agent_trace t ON t.trace_id=s.trace_id WHERE s.trace_id IS NOT NULL AND t.trace_id IS NULL" warn
fi

# 可选 API 验证：API 必须已经连接到目标库，否则只是当前实例 smoke，不代表恢复库。
if [ -n "$API_BASE" ]; then
  API_BASE="${API_BASE%/}"
  command -v curl >/dev/null 2>&1 || die "缺少 curl"
  log "API smoke：${API_BASE}（必须已连接到 ${TARGET_DB}）"
  HEALTH="$(curl -fsS --max-time 10 "$API_BASE/actuator/health" 2>/dev/null || true)"
  if printf '%s' "$HEALTH" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    ok "API health=UP"
  else
    bad "API health 非 UP"
  fi
  if [ -n "$TOKEN" ]; then
    AUDIT_JSON="$(curl -fsS --max-time 15 "$API_BASE/api/v1/audit/verify" \
      -H "Authorization: Bearer $TOKEN" 2>/dev/null || true)"
    if python3 - "$AUDIT_JSON" <<'PY'
import json, sys
try:
    body = json.loads(sys.argv[1] or '{}')
    data = body.get('data') or {}
    sys.exit(0 if data.get('valid') is True else 1)
except Exception:
    sys.exit(1)
PY
    then
      ok "API 审计链 valid=true"
    else
      bad "API 审计链校验失败或返回不可解析"
    fi
    AGENT_JSON="$(curl -fsS --max-time 15 "$API_BASE/api/v1/agent?page=1&pageSize=1" \
      -H "Authorization: Bearer $TOKEN" 2>/dev/null || true)"
    if python3 - "$AGENT_JSON" <<'PY'
import json, sys
try:
    body = json.loads(sys.argv[1] or '{}')
    sys.exit(0 if body.get('code') == 0 and isinstance((body.get('data') or {}).get('list'), list) else 1)
except Exception:
    sys.exit(1)
PY
    then
      ok "API Agent 列表可读"
    else
      bad "API Agent 列表不可读"
    fi
  else
    warn "未提供 PITR_API_TOKEN，跳过审计链/Agent 列表 API 校验"
  fi
else
  warn "未提供 --api-base，跳过 API smoke（仅完成数据库级校验）"
fi

if [ "$FAILED" -gt 0 ]; then
  log "结果：FAIL（$FAILED 项）"
  exit 1
fi
log "结果：PASS（数据库业务级校验通过）"
