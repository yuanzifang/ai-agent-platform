#!/usr/bin/env bash
# MySQL PITR 恢复：校验 base backup → 重建目标库 → 导入 dump → 回放 binlog。
# 默认 dry-run；必须显式 --apply，且建议先停止后端写入并做一次当前备份。
#
# 用法：
#   ./scripts/pitr-restore.sh --backup backups/latest --binlog-dir backups/binlog
#   ./scripts/pitr-restore.sh --backup backups/latest --binlog-dir backups/binlog \
#     --stop-datetime '2026-10-27 12:00:00' --apply --yes --recreate \
#     --target-db ai_agent_platform_pitr_test
# 重建默认生产库需要额外 --allow-production
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

BACKUP_ARG=""
BINLOG_DIR=""
STOP_DATETIME=""
APPLY=0
ASSUME_YES=0
RECREATE=0
ALLOW_PRODUCTION=0
TARGET_DB=""
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP_ARG="${2:?--backup 需要目录}"; shift ;;
    --binlog-dir) BINLOG_DIR="${2:?--binlog-dir 需要目录}"; shift ;;
    --stop-datetime) STOP_DATETIME="${2:?--stop-datetime 需要时间}"; shift ;;
    --target-db) TARGET_DB="${2:?--target-db 需要库名}"; shift ;;
    --apply) APPLY=1 ;;
    --yes) ASSUME_YES=1 ;;
    --recreate) RECREATE=1 ;;
    --allow-production) ALLOW_PRODUCTION=1 ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }
START_EPOCH="$(date +%s)"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-ai_agent_platform}"
MYSQL_BACKUP_USER="${MYSQL_BACKUP_USER:-${MYSQL_USERNAME:-root}}"
MYSQL_BACKUP_PASSWORD="${MYSQL_BACKUP_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-root}}}"
MYSQL_BINLOG="${MYSQL_BINLOG:-mysqlbinlog}"
TARGET_DB="${TARGET_DB:-$MYSQL_DATABASE}"
export MYSQL_PWD="$MYSQL_BACKUP_PASSWORD"

# 防误删生产库：重建默认库必须显式承认；演练请用 *_drill / *_pitr 临时库。
if [ "$APPLY" = "1" ] && [ "$RECREATE" = "1" ]     && [ "$TARGET_DB" = "$MYSQL_DATABASE" ] && [ "$ALLOW_PRODUCTION" != "1" ]; then
  die "拒绝重建生产库 $TARGET_DB；请用 --target-db <临时库>，或显式加 --allow-production"
fi

BACKUP_ROOT="${BACKUP_ROOT:-$ROOT/backups}"
if [ -z "$BACKUP_ARG" ]; then
  BACKUP="$BACKUP_ROOT/latest"
else
  case "$BACKUP_ARG" in
    /*) BACKUP="$BACKUP_ARG" ;;
    *) BACKUP="$ROOT/$BACKUP_ARG" ;;
  esac
fi
[ -f "$BACKUP/manifest.json" ] || die "不是有效备份（缺 manifest.json）：$BACKUP"
[ -f "$BACKUP/SHA256SUMS" ] || die "不是有效备份（缺 SHA256SUMS）：$BACKUP"
[ -f "$BACKUP/mysql/binlog.txt" ] || die "备份缺 binlog 起点：$BACKUP/mysql/binlog.txt"
read -r START_FILE START_POS < <(tail -n +2 "$BACKUP/mysql/binlog.txt" | awk 'NF>=2{print $1, $2; exit}')
[ -n "${START_FILE:-}" ] && [ -n "${START_POS:-}" ] || die "无法解析 binlog 起点"

if [ -z "$BINLOG_DIR" ]; then
  if [ -d "$BACKUP/mysql/binlog" ]; then BINLOG_DIR="$BACKUP/mysql/binlog"
  else BINLOG_DIR="$BACKUP_ROOT/binlog"; fi
fi
[ -d "$BINLOG_DIR" ] || die "binlog 目录不存在：$BINLOG_DIR"

command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
command -v gunzip >/dev/null 2>&1 || die "缺少 gunzip"
command -v "$MYSQL_BINLOG" >/dev/null 2>&1 || die "缺少 mysqlbinlog"
python3 - "$BACKUP" <<'PYV'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
bad = []
for line in (root / 'SHA256SUMS').read_text().splitlines():
    if not line.strip(): continue
    digest, rel = line.split('  ', 1)
    p = root / rel
    if not p.is_file():
        bad.append(rel); continue
    h = hashlib.sha256(p.read_bytes()).hexdigest()
    if h != digest: bad.append(rel)
if bad:
    print('SHA256 mismatch: ' + ', '.join(bad), file=sys.stderr); sys.exit(1)
print(f'backup checksum OK ({len((root / "SHA256SUMS").read_text().splitlines())} files)')
PYV

FILES=()
for f in "$BINLOG_DIR"/mysql-bin.* "$BINLOG_DIR"/binlog.*; do
  [ -f "$f" ] || continue
  name="$(basename "$f")"
  case "$name" in
    "$START_FILE"|"${START_FILE%.*}".*) ;;
  esac
  # 只回放从起点文件开始的文件（兼容 mysql-bin.* 与 binlog.* 命名），跳过更早文件
  if [[ ! "$name" < "$START_FILE" ]]; then FILES+=("$f"); fi
done
[ "${#FILES[@]}" -gt 0 ] || die "没有找到起点文件及之后的 binlog：$START_FILE"
FILES=($(printf '%s\n' "${FILES[@]}" | sort))

log "目标库：$TARGET_DB@$MYSQL_HOST:$MYSQL_PORT"
log "base：$BACKUP/mysql/$MYSQL_DATABASE.sql.gz"
log "起点：${START_FILE}:${START_POS}；binlog：${#FILES[@]} 个"
[ -n "$STOP_DATETIME" ] && log "停止时间：$STOP_DATETIME"
log "binlog 文件：${FILES[*]}"
if [ "$APPLY" != "1" ]; then
  log "dry-run：未修改数据库；加 --apply 执行恢复"
  exit 0
fi
if [ "$ASSUME_YES" != "1" ]; then
  printf '将修改 MySQL 库 %s。输入 yes 继续：' "$TARGET_DB"
  read -r answer
  [ "$answer" = "yes" ] || die "已取消"
fi

MYSQL=(mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_BACKUP_USER")
"${MYSQL[@]}" -e "SELECT 1" >/dev/null 2>&1 || die "MySQL 连接失败"
read -r MANIFEST_TABLES MANIFEST_AUDIT < <(python3 - "$BACKUP/manifest.json" <<'PYM'
import json, sys
m = json.load(open(sys.argv[1]))
mysql = m.get('backends', {}).get('mysql', {})
print(int(mysql.get('tables') or 0), int(mysql.get('auditRows') or 0))
PYM
)
[ -n "${MANIFEST_TABLES:-}" ] || MANIFEST_TABLES=0
[ -n "${MANIFEST_AUDIT:-}" ] || MANIFEST_AUDIT=0
if [ "$RECREATE" = "1" ]; then
  log "重建目标库（DROP/CREATE）"
  "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`; CREATE DATABASE \`$TARGET_DB\`"
else
  die "为避免覆盖现有数据，--apply 必须配合 --recreate（或先手工准备空库）"
fi
log "导入 base dump"
gunzip -c "$BACKUP/mysql/$MYSQL_DATABASE.sql.gz" | "${MYSQL[@]}" "$TARGET_DB"

first=1
for f in "${FILES[@]}"; do
  log "回放 $(basename "$f")"
  args=(--database="$TARGET_DB" --skip-gtids)
  [ "$first" = "1" ] && args+=(--start-position="$START_POS")
  [ -n "$STOP_DATETIME" ] && args+=(--stop-datetime="$STOP_DATETIME")
  "$MYSQL_BINLOG" "${args[@]}" "$f" | "${MYSQL[@]}" "$TARGET_DB"
  first=0
done

# 恢复后基础校验：表数、审计行数、Flyway 版本
TABLE_COUNT="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}'")"
log "校验：表数 ${TABLE_COUNT}（备份基线 ${MANIFEST_TABLES}）"
[ "$TABLE_COUNT" -ge "$MANIFEST_TABLES" ] || die "PITR 后表数少于备份基线（${TABLE_COUNT} < ${MANIFEST_TABLES}）"
AUDIT_EXISTS="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_name='audit_operation'")"
if [ "$AUDIT_EXISTS" = "1" ]; then
  AUDIT_COUNT="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM \`${TARGET_DB}\`.audit_operation")"
  log "校验：审计行数 ${AUDIT_COUNT}（备份基线 ${MANIFEST_AUDIT}）"
  [ "$AUDIT_COUNT" -ge "$MANIFEST_AUDIT" ] || die "PITR 后审计行数少于备份基线（${AUDIT_COUNT} < ${MANIFEST_AUDIT}）"
fi
FLYWAY_VERSION="$("${MYSQL[@]}" -N -B -e "SELECT MAX(version) FROM \`${TARGET_DB}\`.flyway_schema_history" 2>/dev/null || true)"
[ -n "$FLYWAY_VERSION" ] && log "校验：Flyway 版本 ${FLYWAY_VERSION}"
ELAPSED=$(( $(date +%s) - START_EPOCH ))
log "PITR 完成：${TARGET_DB}；本地恢复耗时约 ${ELAPSED}s（不含应用切换/业务验证）"
log "检查：SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}';"
