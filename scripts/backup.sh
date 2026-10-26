#!/usr/bin/env bash
# AI Agent Platform 备份脚本：MySQL + PostgreSQL/pgvector + MinIO
#
# 设计原则：
#   1. 不备份 .env / 密钥；密钥由独立密管系统保存。
#   2. 先写 .partial 目录，全部成功后再原子改名为正式备份。
#   3. 生成 manifest.json + SHA256SUMS，恢复演练脚本会先校验完整性。
#   4. 默认不停机：MySQL 用 --single-transaction；严格一致性演练请低峰/停写执行。
#
# 用法：
#   ./scripts/backup.sh
#   ./scripts/backup.sh --skip-minio
#   BACKUP_ROOT=/data/backups ./scripts/backup.sh --prune --retention-days 14
#   # 异地加密上传（MinIO/S3 兼容，mc）
#   BACKUP_OFFSITE_ENDPOINT=https://minio.example.com \
#   BACKUP_OFFSITE_BUCKET=platform-backups \
#   BACKUP_OFFSITE_ACCESS_KEY=... BACKUP_OFFSITE_SECRET_KEY=... \
#   BACKUP_ENCRYPT_KEY_FILE=/secure/backup.key ./scripts/backup.sh
#
# 依赖：mysqldump/mysql、pg_dump/psql/pg_restore、mc（MinIO Client）、gzip、python3、openssl、tar
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

SKIP_MYSQL=0
SKIP_PG=0
SKIP_MINIO=0
DO_PRUNE=0
SKIP_OFFSITE=0
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
OFFSITE_ARCHIVE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-mysql) SKIP_MYSQL=1 ;;
    --skip-pg|--skip-postgres) SKIP_PG=1 ;;
    --skip-minio) SKIP_MINIO=1 ;;
    --prune) DO_PRUNE=1 ;;
    --skip-offsite) SKIP_OFFSITE=1 ;;
    --retention-days) RETENTION_DAYS="${2:?--retention-days 需要天数}"; shift ;;
    -h|--help)
      sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

BACKUP_ROOT="${BACKUP_ROOT:-$ROOT/backups}"
TS="$(date '+%Y%m%d-%H%M%S')"
OUT="$BACKUP_ROOT/backup-$TS"
PARTIAL="$OUT.partial"
LOCK="$BACKUP_ROOT/.backup.lock"
MC_TMP=""
MC_OFFSITE_TMP=""

mkdir -p "$BACKUP_ROOT"
if ! mkdir "$LOCK" 2>/dev/null; then
  die "已有备份在运行（锁目录：$LOCK）；确认无进程后可手动删除该目录"
fi
if [ -e "$OUT" ] || [ -e "$PARTIAL" ]; then
  rmdir "$LOCK" 2>/dev/null || true
  die "备份目录已存在：$OUT"
fi
mkdir "$PARTIAL"

cleanup() {
  local status=$?
  trap - EXIT
  if [ -n "$MC_TMP" ] && [ -d "$MC_TMP" ]; then rm -rf "$MC_TMP"; fi
  if [ -n "${MC_OFFSITE_TMP:-}" ] && [ -d "$MC_OFFSITE_TMP" ]; then rm -rf "$MC_OFFSITE_TMP"; fi
  if [ "$status" -ne 0 ] && [ -d "$PARTIAL" ]; then
    log "备份失败，删除未完成目录：$PARTIAL"
    rm -rf "$PARTIAL"
  fi
  rmdir "$LOCK" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT

WARNINGS="$PARTIAL/warnings.txt"
: > "$WARNINGS"
warn() { log "WARN: $*"; printf '%s\n' "$*" >> "$WARNINGS"; }

MYSQL_TABLE_COUNT=0
MYSQL_AUDIT_ROWS=0
MYSQL_DUMP_VERSION="skipped"
PG_TABLE_COUNT=0
PG_DUMP_VERSION="skipped"
MINIO_OBJECT_COUNT=0
MINIO_BYTES=0
MINIO_BUCKET_USED="skipped"

# ---------------- MySQL ----------------
if [ "$SKIP_MYSQL" = "1" ]; then
  warn "MySQL 备份已通过 --skip-mysql 跳过"
else
  command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
  command -v mysqldump >/dev/null 2>&1 || die "缺少 mysqldump"
  command -v gzip >/dev/null 2>&1 || die "缺少 gzip"

  MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
  MYSQL_PORT="${MYSQL_PORT:-3306}"
  MYSQL_DATABASE="${MYSQL_DATABASE:-ai_agent_platform}"
  MYSQL_BACKUP_USER="${MYSQL_BACKUP_USER:-${MYSQL_USERNAME:-root}}"
  MYSQL_BACKUP_PASSWORD="${MYSQL_BACKUP_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-root}}}"
  export MYSQL_PWD="$MYSQL_BACKUP_PASSWORD"

  MYSQL=(mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_BACKUP_USER")
  MYSQLDUMP=(mysqldump --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_BACKUP_USER"
    --single-transaction --quick --skip-lock-tables --no-tablespaces
    --routines --triggers --events --hex-blob
    --default-character-set=utf8mb4 --set-gtid-purged=OFF --column-statistics=0)

  "${MYSQL[@]}" -N -e "SELECT 1" >/dev/null 2>&1 \
    || die "MySQL 连接失败：${MYSQL_BACKUP_USER}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}；请检查 MYSQL_* 配置"

  mkdir -p "$PARTIAL/mysql"
  log "MySQL：导出 $MYSQL_DATABASE 到 $PARTIAL/mysql"

  "${MYSQL[@]}" --batch --skip-column-names -e \
    "SELECT table_name FROM information_schema.tables WHERE table_schema='$MYSQL_DATABASE' AND table_type='BASE TABLE' ORDER BY table_name" \
    > "$PARTIAL/mysql/tables.txt"
  MYSQL_TABLE_COUNT=$(wc -l < "$PARTIAL/mysql/tables.txt" | tr -d ' ')
  [ "$MYSQL_TABLE_COUNT" -gt 0 ] || die "MySQL 库 $MYSQL_DATABASE 没有表"

  : > "$PARTIAL/mysql/checksums.tsv"
  while IFS= read -r table; do
    [ -n "$table" ] || continue
    checksum="$("${MYSQL[@]}" --batch --skip-column-names -e "CHECKSUM TABLE \`$MYSQL_DATABASE\`.\`$table\`" | awk '{print $2}')"
    printf '%s\t%s\n' "$table" "$checksum" >> "$PARTIAL/mysql/checksums.tsv"
  done < "$PARTIAL/mysql/tables.txt"

  MYSQL_AUDIT_ROWS="$("${MYSQL[@]}" --batch --skip-column-names -e \
    "SELECT COUNT(*) FROM \`$MYSQL_DATABASE\`.audit_operation" 2>/dev/null || echo 0)"

  # 可选 PITR 坐标；权限不足时不阻断备份
  if ! "${MYSQL[@]}" -e "SHOW MASTER STATUS\G" > "$PARTIAL/mysql/binlog.txt" 2>/dev/null; then
    : > "$PARTIAL/mysql/binlog.txt"
    warn "无法读取 MySQL binlog 坐标（权限不足或未开启 binlog）"
  fi

  if ! "${MYSQLDUMP[@]}" "$MYSQL_DATABASE" 2> "$PARTIAL/mysql/dump.err" \
      | gzip -9 > "$PARTIAL/mysql/$MYSQL_DATABASE.sql.gz"; then
    cat "$PARTIAL/mysql/dump.err" >&2 || true
    die "mysqldump 失败"
  fi
  gzip -t "$PARTIAL/mysql/$MYSQL_DATABASE.sql.gz" || die "MySQL 备份 gzip 校验失败"
  rm -f "$PARTIAL/mysql/dump.err"
  MYSQL_DUMP_VERSION="$(mysqldump --version 2>/dev/null || echo unknown)"
  log "MySQL：完成，$MYSQL_TABLE_COUNT 张表，dump $(du -h "$PARTIAL/mysql/$MYSQL_DATABASE.sql.gz" | awk '{print $1}')"
fi

# ---------------- PostgreSQL / pgvector ----------------
if [ "$SKIP_PG" = "1" ]; then
  warn "PgVector 备份已通过 --skip-pg 跳过"
else
  command -v psql >/dev/null 2>&1 || die "缺少 psql"
  command -v pg_dump >/dev/null 2>&1 || die "缺少 pg_dump"

  PG_DUMP="${PG_DUMP:-pg_dump}"
  if [ -z "${PG_RESTORE:-}" ] && [ "$PG_DUMP" != "pg_dump" ] \
      && [ -x "$(dirname "$PG_DUMP")/pg_restore" ]; then
    # pg_dump 17 的 custom archive 需要同版本 pg_restore
    PG_RESTORE="$(dirname "$PG_DUMP")/pg_restore"
  fi
  PG_RESTORE="${PG_RESTORE:-pg_restore}"
  [ -x "$PG_RESTORE" ] || command -v "$PG_RESTORE" >/dev/null 2>&1 || die "缺少 pg_restore：$PG_RESTORE"
  PSQL="${PSQL:-psql}"

  # 兼容应用配置的 PG_URL=jdbc:postgresql://host:5432/db
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
    PG_DATABASE="${PG_DATABASE:-$_pg_db}"
  fi
  PG_HOST="${PG_HOST:-127.0.0.1}"
  PG_PORT="${PG_PORT:-5432}"
  PG_DATABASE="${PG_DATABASE:-vector}"
  PG_USERNAME="${PG_USERNAME:-$(id -un)}"
  export PGPASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"

  PSQL_CMD=("$PSQL" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USERNAME" -d "$PG_DATABASE" -v ON_ERROR_STOP=1 --no-psqlrc)
  "${PSQL_CMD[@]}" -Atc "SELECT 1" >/dev/null 2>&1 \
    || die "PostgreSQL 连接失败：${PG_USERNAME}@${PG_HOST}:${PG_PORT}/${PG_DATABASE}；请检查 PG_* 配置"

  server_num="$("${PSQL_CMD[@]}" -Atc 'SHOW server_version_num')"
  server_major=$((server_num / 10000))
  dump_major="$("$PG_DUMP" --version | awk '{print $3}' | cut -d. -f1)"
  if [ "$dump_major" -lt "$server_major" ]; then
    die "pg_dump 主版本 ${dump_major} 低于 PostgreSQL 服务端 ${server_major}；请设置 PG_DUMP=/path/to/pg_dump-${server_major}"
  fi

  mkdir -p "$PARTIAL/pgvector"
  log "PgVector：导出 $PG_DATABASE 到 $PARTIAL/pgvector"
  "$PG_DUMP" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USERNAME" -d "$PG_DATABASE" \
    --format=custom --no-owner --no-privileges --compress=9 \
    -f "$PARTIAL/pgvector/$PG_DATABASE.dump" \
    || die "pg_dump 失败"
  "$PG_RESTORE" --list "$PARTIAL/pgvector/$PG_DATABASE.dump" > "$PARTIAL/pgvector/toc.txt" \
    || die "pg_restore --list 校验失败"

  # 精确计数（不依赖 ANALYZE 的估算值）
  "${PSQL_CMD[@]}" -At -F $'\t' -c "
    SELECT n.nspname || '.' || c.relname,
           (xpath('/row/c/text()', query_to_xml(
             format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
             false, true, '')))[1]::text::bigint
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r'
      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      AND n.nspname NOT LIKE 'pg_toast%'
    ORDER BY 1" > "$PARTIAL/pgvector/counts.tsv"
  PG_TABLE_COUNT=$(wc -l < "$PARTIAL/pgvector/counts.tsv" | tr -d ' ')
  PG_DUMP_VERSION="$("$PG_DUMP" --version 2>/dev/null || echo unknown)"
  log "PgVector：完成，$PG_TABLE_COUNT 张表，dump $(du -h "$PARTIAL/pgvector/$PG_DATABASE.dump" | awk '{print $1}')"
fi

# ---------------- MinIO ----------------
if [ "$SKIP_MINIO" = "1" ]; then
  warn "MinIO 备份已通过 --skip-minio 跳过"
else
  command -v mc >/dev/null 2>&1 || die "缺少 mc（MinIO Client）；可用 --skip-minio 跳过"
  MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://127.0.0.1:9000}"
  MINIO_ACCESS="${MINIO_ACCESS_KEY:-${MINIO_ROOT_USER:-minioadmin}}"
  MINIO_SECRET="${MINIO_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-minioadmin}}"
  MINIO_BUCKET="${MINIO_BUCKET:-ai-agent-platform}"
  MINIO_BUCKET_USED="$MINIO_BUCKET"

  MC_TMP="$(mktemp -d "${TMPDIR:-/tmp}/aiagent-mc.XXXXXX")"
  export MC_CONFIG_DIR="$MC_TMP"
  mc alias set backup "$MINIO_ENDPOINT" "$MINIO_ACCESS" "$MINIO_SECRET" >/dev/null 2>&1 \
    || die "MinIO mc alias 配置失败：$MINIO_ENDPOINT"
  mc ls "backup/$MINIO_BUCKET" >/dev/null 2>&1 \
    || die "MinIO bucket 不存在或无权限：$MINIO_BUCKET"

  mkdir -p "$PARTIAL/minio/$MINIO_BUCKET"
  log "MinIO：镜像 bucket $MINIO_BUCKET 到 $PARTIAL/minio/$MINIO_BUCKET"
  if ! mc mirror --overwrite --preserve --retry --summary "backup/$MINIO_BUCKET" \
      "$PARTIAL/minio/$MINIO_BUCKET" > "$PARTIAL/minio/mirror.log" 2>&1; then
    tail -50 "$PARTIAL/minio/mirror.log" >&2 || true
    die "mc mirror 失败"
  fi
  mc ls --recursive --json "backup/$MINIO_BUCKET" > "$PARTIAL/minio/objects.jsonl" 2>/dev/null \
    || die "读取 MinIO 对象清单失败"

  # 比对象名 + 大小（mc diff 只比较时间，不能作为内容完整性依据）
  python3 - "$PARTIAL/minio/$MINIO_BUCKET" "$PARTIAL/minio/objects.jsonl" <<'PY2'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
remote = {}
for line in pathlib.Path(sys.argv[2]).read_text().splitlines():
    if not line.strip():
        continue
    o = json.loads(line)
    key = o.get('key')
    if not key or key.endswith('/') or o.get('type') == 'folder':
        continue
    remote[key] = int(o.get('size') or 0)
local = {p.relative_to(root).as_posix(): p.stat().st_size
         for p in root.rglob('*') if p.is_file()}
missing = sorted(set(remote) - set(local))
extra = sorted(set(local) - set(remote))
size_mismatch = sorted(k for k in set(remote) & set(local) if remote[k] != local[k])
if missing or extra or size_mismatch:
    print(f'missing={missing[:10]} extra={extra[:10]} size_mismatch={size_mismatch[:10]}', file=sys.stderr)
    sys.exit(1)
print(f'OK objects={len(local)} bytes={sum(local.values())}')
PY2
  MINIO_OBJECT_COUNT=$(find "$PARTIAL/minio/$MINIO_BUCKET" -type f | wc -l | tr -d ' ')
  MINIO_BYTES=$(python3 - "$PARTIAL/minio/$MINIO_BUCKET" <<'PY2'
import pathlib, sys
print(sum(p.stat().st_size for p in pathlib.Path(sys.argv[1]).rglob('*') if p.is_file()))
PY2
)
  log "MinIO：完成，$MINIO_OBJECT_COUNT 个对象，$(du -sh "$PARTIAL/minio/$MINIO_BUCKET" | awk '{print $1}')"
fi

# ---------------- manifest / checksums ----------------
log "生成 manifest 与 SHA256SUMS"
cat > "$PARTIAL/README.txt" <<EOF
AI Agent Platform backup
Created: $(date -u '+%Y-%m-%dT%H:%M:%SZ')
Backup format: 1

This directory contains user data. It must be protected like a production database.
Do not commit or upload without encryption.

Local restore drill:
  ./scripts/restore-drill.sh --backup "$OUT"

Production restore: see docs/备份与恢复.md
EOF

export BACKUP_TS_ISO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
export BACKUP_HOSTNAME="$(hostname)"
export BACKUP_GIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
export BACKUP_MYSQL_DB="${MYSQL_DATABASE:-}"
export BACKUP_MYSQL_TABLES="$MYSQL_TABLE_COUNT"
export BACKUP_MYSQL_AUDIT_ROWS="$MYSQL_AUDIT_ROWS"
export BACKUP_MYSQL_VERSION="$MYSQL_DUMP_VERSION"
export BACKUP_PG_DB="${PG_DATABASE:-}"
export BACKUP_PG_TABLES="$PG_TABLE_COUNT"
export BACKUP_PG_VERSION="$PG_DUMP_VERSION"
export BACKUP_MINIO_BUCKET="$MINIO_BUCKET_USED"
export BACKUP_MINIO_OBJECTS="$MINIO_OBJECT_COUNT"
export BACKUP_MINIO_BYTES="$MINIO_BYTES"

python3 - "$PARTIAL" <<'PY'
import hashlib, json, os, pathlib, sys

root = pathlib.Path(sys.argv[1])
files = {}
for p in sorted(root.rglob('*')):
    if not p.is_file() or p.name in ('manifest.json', 'SHA256SUMS'):
        continue
    rel = p.relative_to(root).as_posix()
    h = hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    files[rel] = {'bytes': p.stat().st_size, 'sha256': h.hexdigest()}

warnings = []
wp = root / 'warnings.txt'
if wp.is_file():
    warnings = [line for line in wp.read_text().splitlines() if line.strip()]

manifest = {
    'format': 'ai-agent-platform-backup/1',
    'createdAt': os.environ['BACKUP_TS_ISO'],
    'hostname': os.environ['BACKUP_HOSTNAME'],
    'gitCommit': os.environ['BACKUP_GIT'],
    'backends': {
        'mysql': {
            'database': os.environ.get('BACKUP_MYSQL_DB') or None,
            'tables': int(os.environ.get('BACKUP_MYSQL_TABLES') or 0),
            'auditRows': int(os.environ.get('BACKUP_MYSQL_AUDIT_ROWS') or 0),
            'dumpVersion': os.environ.get('BACKUP_MYSQL_VERSION') or None,
        },
        'pgvector': {
            'database': os.environ.get('BACKUP_PG_DB') or None,
            'tables': int(os.environ.get('BACKUP_PG_TABLES') or 0),
            'dumpVersion': os.environ.get('BACKUP_PG_VERSION') or None,
        },
        'minio': {
            'bucket': os.environ.get('BACKUP_MINIO_BUCKET') or None,
            'objects': int(os.environ.get('BACKUP_MINIO_OBJECTS') or 0),
            'bytes': int(os.environ.get('BACKUP_MINIO_BYTES') or 0),
        },
    },
    'warnings': warnings,
    'files': files,
}
(root / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')

manifest_path = root / 'manifest.json'
manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
with (root / 'SHA256SUMS').open('w') as out:
    for rel, meta in files.items():
        out.write(f"{meta['sha256']}  {rel}\n")
    # manifest 本身也必须被校验，防止对象数量/库名被意外篡改
    out.write(f"{manifest_hash}  manifest.json\n")
print(f"manifest: {len(files)} files (+ manifest.json)")
PY

chmod -R go-rwx "$PARTIAL"
mv "$PARTIAL" "$OUT"
ln -sfn "backup-$TS" "$BACKUP_ROOT/latest"

if [ "$DO_PRUNE" = "1" ]; then
  if ! [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
    log "WARN: 保留天数非法，跳过清理：$RETENTION_DAYS"
  else
    log "清理超过 $RETENTION_DAYS 天的本地备份"
    find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'backup-*' \
      ! -name "backup-$TS" -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} \;
  fi
fi

# ---------------- 异地/对象存储副本 ----------------
if [ "$SKIP_OFFSITE" = "0" ] && { [ -n "${BACKUP_OFFSITE_ENDPOINT:-}" ] || [ -n "${BACKUP_OFFSITE_BUCKET:-}" ]; }; then
  [ -n "${BACKUP_OFFSITE_ENDPOINT:-}" ] || die "BACKUP_OFFSITE_ENDPOINT 未配置"
  [ -n "${BACKUP_OFFSITE_BUCKET:-}" ] || die "BACKUP_OFFSITE_BUCKET 未配置"
  [ -n "${BACKUP_OFFSITE_ACCESS_KEY:-}" ] || die "BACKUP_OFFSITE_ACCESS_KEY 未配置"
  [ -n "${BACKUP_OFFSITE_SECRET_KEY:-}" ] || die "BACKUP_OFFSITE_SECRET_KEY 未配置"
  command -v mc >/dev/null 2>&1 || die "异地备份需要 mc（MinIO Client）"
  command -v tar >/dev/null 2>&1 || die "异地备份需要 tar"
  [ -n "${BACKUP_ENCRYPT_KEY_FILE:-}" ] || die "异地备份要求 BACKUP_ENCRYPT_KEY_FILE（备份加密密钥文件）"
  [ -r "$BACKUP_ENCRYPT_KEY_FILE" ] || die "备份加密密钥不可读：$BACKUP_ENCRYPT_KEY_FILE"
  command -v openssl >/dev/null 2>&1 || die "备份加密需要 openssl"

  BACKUP_OFFSITE_PREFIX="${BACKUP_OFFSITE_PREFIX:-ai-agent-platform}"
  MC_OFFSITE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/aiagent-mc-offsite.XXXXXX")"
  export MC_CONFIG_DIR="$MC_OFFSITE_TMP"
  mc alias set offsite "$BACKUP_OFFSITE_ENDPOINT" "$BACKUP_OFFSITE_ACCESS_KEY"     "$BACKUP_OFFSITE_SECRET_KEY" >/dev/null 2>&1 \
    || die "异地 mc alias 配置失败：$BACKUP_OFFSITE_ENDPOINT"
  mc mb --ignore-existing "offsite/$BACKUP_OFFSITE_BUCKET" >/dev/null 2>&1 \
    || die "异地 bucket 不可用：$BACKUP_OFFSITE_BUCKET"

  log "异地备份：打包并加密 $OUT"
  OFFSITE_ARCHIVE="$BACKUP_ROOT/backup-$TS.tar.gz"
  tar -C "$OUT" -czf "$OFFSITE_ARCHIVE" .
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$BACKUP_ENCRYPT_KEY_FILE" \
    -in "$OFFSITE_ARCHIVE" -out "$OFFSITE_ARCHIVE.enc"
  rm -f "$OFFSITE_ARCHIVE"
  OFFSITE_ARCHIVE="$OFFSITE_ARCHIVE.enc"
  OBJECT="$BACKUP_OFFSITE_PREFIX/backup-$TS.tar.gz.enc"
  mc cp "$OFFSITE_ARCHIVE" "offsite/$BACKUP_OFFSITE_BUCKET/$OBJECT" >/dev/null \
    || die "异地上传失败：$OBJECT"
  local_hash="$(python3 - "$OFFSITE_ARCHIVE" <<'PYH'
import hashlib, pathlib, sys
h = hashlib.sha256()
with open(pathlib.Path(sys.argv[1]), 'rb') as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b''):
        h.update(chunk)
print(h.hexdigest())
PYH
)"
  remote_hash="$(mc cat "offsite/$BACKUP_OFFSITE_BUCKET/$OBJECT" | python3 -c 'import hashlib,sys; h=hashlib.sha256(); [h.update(c) for c in iter(lambda: sys.stdin.buffer.read(1024*1024), b"")]; print(h.hexdigest())')"
  [ "$local_hash" = "$remote_hash" ] || die "异地副本 SHA256 校验失败：$OBJECT"
  rm -f "$OFFSITE_ARCHIVE"
  OFFSITE_ARCHIVE=""
  log "异地备份完成：offsite/${BACKUP_OFFSITE_BUCKET}/${OBJECT}（SHA256 ${local_hash}）"
fi

log "备份完成：$OUT"
log "大小：$(du -sh "$OUT" | awk '{print $1}')；校验文件：$OUT/SHA256SUMS"
