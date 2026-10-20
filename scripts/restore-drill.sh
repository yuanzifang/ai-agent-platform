#!/usr/bin/env bash
# AI Agent Platform 恢复演练：校验备份完整性，并恢复到一个临时库/临时 bucket 做比对。
#
# 只允许写入临时资源：
#   MySQL     ai_agent_platform_drill_*
#   pgvector  vector_drill_*
#   MinIO     restore-drill-*
# 演练结束（含失败）会自动删除这些临时资源。
#
# 用法：
#   ./scripts/restore-drill.sh
#   ./scripts/restore-drill.sh --backup backups/backup-20261020-120000
#   ./scripts/restore-drill.sh --allow-drift   # 备份期间有写入时，只校验表结构与行数可恢复
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
ALLOW_DRIFT=0
SKIP_MYSQL=0
SKIP_PG=0
SKIP_MINIO=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP_ARG="${2:?--backup 需要目录}"; shift ;;
    --allow-drift) ALLOW_DRIFT=1 ;;
    --skip-mysql) SKIP_MYSQL=1 ;;
    --skip-pg|--skip-postgres) SKIP_PG=1 ;;
    --skip-minio) SKIP_MINIO=1 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

BACKUP_ROOT="${BACKUP_ROOT:-$ROOT/backups}"
if [ -z "$BACKUP_ARG" ]; then
  BACKUP="$BACKUP_ROOT/latest"
else
  case "$BACKUP_ARG" in
    /*) BACKUP="$BACKUP_ARG" ;;
    *) BACKUP="$ROOT/$BACKUP_ARG" ;;
  esac
fi
[ -e "$BACKUP" ] || die "备份目录不存在：$BACKUP"
BACKUP="$(cd "$BACKUP" && pwd -P)"
[ -f "$BACKUP/manifest.json" ] || die "不是有效备份（缺 manifest.json）：$BACKUP"
[ -f "$BACKUP/SHA256SUMS" ] || die "不是有效备份（缺 SHA256SUMS）：$BACKUP"

TS="$(date '+%Y%m%d-%H%M%S')"
SCRATCH_MYSQL=""
SCRATCH_PG=""
SCRATCH_BUCKET=""
MC_TMP=""
DRILL_TMP="$(mktemp -d "${TMPDIR:-/tmp}/aiagent-drill.XXXXXX")"
MYSQL_RESULT="skipped"
PG_RESULT="skipped"
MINIO_RESULT="skipped"

cleanup() {
  local status=$?
  trap - EXIT
  if [ -n "$SCRATCH_MYSQL" ]; then
    log "清理临时 MySQL 库：$SCRATCH_MYSQL"
    "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$SCRATCH_MYSQL\`" >/dev/null 2>&1 || true
  fi
  if [ -n "$SCRATCH_PG" ]; then
    log "清理临时 PgVector 库：$SCRATCH_PG"
    "$PSQL" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USERNAME" -d postgres \
      -v ON_ERROR_STOP=1 --no-psqlrc -c "DROP DATABASE IF EXISTS \"$SCRATCH_PG\"" >/dev/null 2>&1 || true
  fi
  if [ -n "$SCRATCH_BUCKET" ] && [ -n "$MC_TMP" ]; then
    log "清理临时 MinIO bucket：$SCRATCH_BUCKET"
    MC_CONFIG_DIR="$MC_TMP" mc rb --force "backup/$SCRATCH_BUCKET" >/dev/null 2>&1 || true
  fi
  [ -n "$MC_TMP" ] && rm -rf "$MC_TMP"
  rm -rf "$DRILL_TMP"
  exit "$status"
}
trap cleanup EXIT

manifest_get() {
  python3 - "$BACKUP/manifest.json" "$1" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
cur = m
for key in sys.argv[2].split('.'):
    cur = (cur or {}).get(key) if isinstance(cur, dict) else None
print('' if cur is None else cur)
PY
}

log "校验 SHA256SUMS：$BACKUP"
python3 - "$BACKUP" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
errors = []
for line in (root / 'SHA256SUMS').read_text().splitlines():
    if not line.strip():
        continue
    expected, rel = line.split('  ', 1)
    p = (root / rel).resolve()
    if root not in p.parents and p != root:
        errors.append(f'非法路径：{rel}')
        continue
    if not p.is_file():
        errors.append(f'缺文件：{rel}')
        continue
    h = hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    if h.hexdigest() != expected:
        errors.append(f'哈希不匹配：{rel}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print(f'OK：{sum(1 for _ in (root / "SHA256SUMS").read_text().splitlines() if _.strip())} 个文件')
PY

# ---------------- MySQL ----------------
MYSQL_DB="$(manifest_get backends.mysql.database)"
if [ "$SKIP_MYSQL" = "0" ] && [ -n "$MYSQL_DB" ] && [ -f "$BACKUP/mysql/$MYSQL_DB.sql.gz" ]; then
  command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
  MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
  MYSQL_PORT="${MYSQL_PORT:-3306}"
  MYSQL_BACKUP_USER="${MYSQL_BACKUP_USER:-${MYSQL_USERNAME:-root}}"
  MYSQL_BACKUP_PASSWORD="${MYSQL_BACKUP_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-root}}}"
  export MYSQL_PWD="$MYSQL_BACKUP_PASSWORD"
  MYSQL=(mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_BACKUP_USER")
  "${MYSQL[@]}" -N -e "SELECT 1" >/dev/null 2>&1 || die "MySQL 连接失败"

  SCRATCH_MYSQL="ai_agent_platform_drill_${TS}_$$"
  log "MySQL：恢复 $MYSQL_DB 到临时库 $SCRATCH_MYSQL"
  "${MYSQL[@]}" -e "CREATE DATABASE \`$SCRATCH_MYSQL\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" \
    || die "创建临时 MySQL 库失败"
  if ! gunzip -c "$BACKUP/mysql/$MYSQL_DB.sql.gz" \
      | "${MYSQL[@]}" "$SCRATCH_MYSQL" 2> "$DRILL_TMP/mysql-restore.err"; then
    tail -50 "$DRILL_TMP/mysql-restore.err" >&2 || true
    die "MySQL 恢复失败"
  fi

  expected_tables="$(wc -l < "$BACKUP/mysql/checksums.tsv" | tr -d ' ')"
  actual_tables="$("${MYSQL[@]}" --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SCRATCH_MYSQL' AND table_type='BASE TABLE'")"
  [ "$expected_tables" = "$actual_tables" ] || die "MySQL 表数量不一致：期望 ${expected_tables}，实际 ${actual_tables}"

  mismatch=0
  while IFS=$'\t' read -r table expected; do
    [ -n "$table" ] || continue
    actual="$("${MYSQL[@]}" --batch --skip-column-names -e "CHECKSUM TABLE \`$SCRATCH_MYSQL\`.\`$table\`" | awk '{print $2}')"
    if [ "$expected" != "$actual" ]; then
      mismatch=$((mismatch + 1))
      [ "$ALLOW_DRIFT" = "0" ] && printf 'checksum mismatch: %s expected=%s actual=%s\n' "$table" "$expected" "$actual" >&2
    fi
  done < "$BACKUP/mysql/checksums.tsv"
  if [ "$mismatch" -gt 0 ] && [ "$ALLOW_DRIFT" = "0" ]; then
    die "MySQL 有 $mismatch 张表校验和不一致；若备份期间有写入，使用 --allow-drift"
  fi
  audit_rows="$("${MYSQL[@]}" --batch --skip-column-names -e "SELECT COUNT(*) FROM \`$SCRATCH_MYSQL\`.audit_operation" 2>/dev/null || echo 0)"
  MYSQL_RESULT="ok tables=$actual_tables checksum_mismatch=$mismatch audit_rows=$audit_rows"
  log "MySQL：通过（${actual_tables} 张表，校验和不一致 ${mismatch}，审计 ${audit_rows} 条）"
elif [ "$SKIP_MYSQL" = "0" ] && [ -n "$MYSQL_DB" ]; then
  die "备份中缺少 MySQL dump：mysql/$MYSQL_DB.sql.gz"
fi

# ---------------- PgVector ----------------
PG_DB="$(manifest_get backends.pgvector.database)"
if [ "$SKIP_PG" = "0" ] && [ -n "$PG_DB" ] && [ -f "$BACKUP/pgvector/$PG_DB.dump" ]; then
  command -v psql >/dev/null 2>&1 || die "缺少 psql"
  PG_DUMP="${PG_DUMP:-pg_dump}"
  if [ -z "${PG_RESTORE:-}" ] && [ "$PG_DUMP" != "pg_dump" ] \
      && [ -x "$(dirname "$PG_DUMP")/pg_restore" ]; then
    PG_RESTORE="$(dirname "$PG_DUMP")/pg_restore"
  fi
  PG_RESTORE="${PG_RESTORE:-pg_restore}"
  [ -x "$PG_RESTORE" ] || command -v "$PG_RESTORE" >/dev/null 2>&1 || die "缺少 pg_restore：$PG_RESTORE"
  PSQL="${PSQL:-psql}"
  if [ -n "${PG_URL:-}" ]; then
    _pg_url="${PG_URL#jdbc:postgresql://}"
    _pg_hostport="${_pg_url%%/*}"
    _pg_db="${_pg_url#*/}"
    _pg_db="${_pg_db%%\?*}"
    _pg_host="${_pg_hostport%%:*}"
    _pg_port="${_pg_hostport##*:}"
    [ "$_pg_port" = "$_pg_hostport" ] && _pg_port=5432
    PG_HOST="${PG_HOST:-$_pg_host}"
    PG_PORT="${PG_PORT:-$_pg_port}"
    PG_USERNAME="${PG_USERNAME:-}"
  fi
  PG_HOST="${PG_HOST:-127.0.0.1}"
  PG_PORT="${PG_PORT:-5432}"
  PG_USERNAME="${PG_USERNAME:-$(id -un)}"
  export PGPASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"
  PSQL=( "$PSQL" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USERNAME" -v ON_ERROR_STOP=1 --no-psqlrc )

  SCRATCH_PG="vector_drill_${TS}_$$"
  log "PgVector：恢复 $PG_DB 到临时库 $SCRATCH_PG"
  "${PSQL[@]}" -d postgres -c "CREATE DATABASE \"$SCRATCH_PG\"" >/dev/null \
    || die "创建临时 PgVector 库失败"
  if ! "$PG_RESTORE" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USERNAME" -d "$SCRATCH_PG" \
      --no-owner --no-privileges --exit-on-error "$BACKUP/pgvector/$PG_DB.dump" \
      > "$DRILL_TMP/pg-restore.out" 2> "$DRILL_TMP/pg-restore.err"; then
    tail -50 "$DRILL_TMP/pg-restore.err" >&2 || true
    die "PgVector 恢复失败"
  fi

  "${PSQL[@]}" -d "$SCRATCH_PG" -At -F $'\t' -c "
    SELECT n.nspname || '.' || c.relname,
           (xpath('/row/c/text()', query_to_xml(
             format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
             false, true, '')))[1]::text::bigint
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r'
      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      AND n.nspname NOT LIKE 'pg_toast%'
    ORDER BY 1" > "$DRILL_TMP/pg-counts.tsv"
  if ! diff -u "$BACKUP/pgvector/counts.tsv" "$DRILL_TMP/pg-counts.tsv" > "$DRILL_TMP/pg-counts.diff"; then
    if [ "$ALLOW_DRIFT" = "0" ]; then
      cat "$DRILL_TMP/pg-counts.diff" >&2 || true
      die "PgVector 表行数不一致；若备份期间有写入，使用 --allow-drift"
    fi
    log "WARN: PgVector 行数与备份时不同（--allow-drift），恢复本身成功"
  fi
  pg_tables=$(wc -l < "$DRILL_TMP/pg-counts.tsv" | tr -d ' ')
  PG_RESULT="ok tables=$pg_tables"
  log "PgVector：通过（$pg_tables 张表）"
elif [ "$SKIP_PG" = "0" ] && [ -n "$PG_DB" ]; then
  die "备份中缺少 PgVector dump：pgvector/$PG_DB.dump"
fi

# ---------------- MinIO ----------------
MINIO_BUCKET="$(manifest_get backends.minio.bucket)"
if [ "$SKIP_MINIO" = "0" ] && [ -n "$MINIO_BUCKET" ] && [ -d "$BACKUP/minio/$MINIO_BUCKET" ]; then
  command -v mc >/dev/null 2>&1 || die "缺少 mc（MinIO Client）"
  MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:9000}"
  MINIO_ACCESS="${MINIO_ACCESS_KEY:-${MINIO_ROOT_USER:-minioadmin}}"
  MINIO_SECRET="${MINIO_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-minioadmin}}"
  MC_TMP="$(mktemp -d "${TMPDIR:-/tmp}/aiagent-mc-drill.XXXXXX")"
  export MC_CONFIG_DIR="$MC_TMP"
  mc alias set backup "$MINIO_ENDPOINT" "$MINIO_ACCESS" "$MINIO_SECRET" >/dev/null 2>&1 \
    || die "MinIO mc alias 配置失败"

  SCRATCH_BUCKET="restore-drill-${TS}-$$"
  log "MinIO：恢复 bucket $MINIO_BUCKET 到临时 bucket $SCRATCH_BUCKET"
  mc mb "backup/$SCRATCH_BUCKET" >/dev/null || die "创建临时 MinIO bucket 失败"
  if ! mc mirror --overwrite --retry --summary "$BACKUP/minio/$MINIO_BUCKET" "backup/$SCRATCH_BUCKET" \
      > "$DRILL_TMP/minio-restore.out" 2> "$DRILL_TMP/minio-restore.err"; then
    tail -50 "$DRILL_TMP/minio-restore.err" >&2 || true
    die "MinIO 恢复失败"
  fi
  # 从临时 bucket 再下载一份，逐文件 SHA256 对比，验证真实内容而不是仅对象名/时间
  if ! mc mirror --overwrite --retry --summary "backup/$SCRATCH_BUCKET" "$DRILL_TMP/minio-restored" \
      > "$DRILL_TMP/minio-download.out" 2> "$DRILL_TMP/minio-download.err"; then
    tail -50 "$DRILL_TMP/minio-download.err" >&2 || true
    die "MinIO 回读失败"
  fi
  python3 - "$BACKUP/minio/$MINIO_BUCKET" "$DRILL_TMP/minio-restored" <<'PY2'
import hashlib, pathlib, sys

def hashes(root):
    root = pathlib.Path(root)
    out = {}
    if not root.exists():
        return out
    for p in root.rglob('*'):
        if p.is_file():
            h = hashlib.sha256()
            with p.open('rb') as f:
                for chunk in iter(lambda: f.read(1024 * 1024), b''):
                    h.update(chunk)
            out[p.relative_to(root).as_posix()] = (p.stat().st_size, h.hexdigest())
    return out

src, dst = hashes(sys.argv[1]), hashes(sys.argv[2])
if src != dst:
    print(f'source={len(src)} restored={len(dst)}', file=sys.stderr)
    for key in sorted(set(src) | set(dst)):
        if src.get(key) != dst.get(key):
            print(f'mismatch: {key} {src.get(key)} != {dst.get(key)}', file=sys.stderr)
    sys.exit(1)
print(f'OK objects={len(src)} bytes={sum(v[0] for v in src.values())}')
PY2
  local_count=$(find "$BACKUP/minio/$MINIO_BUCKET" -type f | wc -l | tr -d ' ')
  expected_count="$(manifest_get backends.minio.objects)"
  [ -z "$expected_count" ] && expected_count=0
  [ "$local_count" = "$expected_count" ] || die "MinIO 对象数不一致：manifest=$expected_count local=$local_count"
  MINIO_RESULT="ok objects=$local_count sha256=matched"
  log "MinIO：通过（$local_count 个对象，SHA256 一致）"
elif [ "$SKIP_MINIO" = "0" ] && [ -n "$MINIO_BUCKET" ]; then
  die "备份中缺少 MinIO 目录：minio/$MINIO_BUCKET"
fi

log "恢复演练通过"
printf 'DRILL_OK\nMySQL: %s\nPgVector: %s\nMinIO: %s\n' "$MYSQL_RESULT" "$PG_RESULT" "$MINIO_RESULT"
