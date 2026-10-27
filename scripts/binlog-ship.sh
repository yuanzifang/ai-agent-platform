#!/usr/bin/env bash
# MySQL binlog 持续归档：把 binlog 下载到本地备份目录，可选加密上传到 MinIO/S3。
# 建议 cron 每分钟运行；配合 backup.sh 的 base dump 可实现 PITR。
#
# 用法：
#   ./scripts/binlog-ship.sh
#   BINLOG_ARCHIVE_DIR=/data/binlog ./scripts/binlog-ship.sh
#
# 依赖：mysql、mysqlbinlog、gzip、python3；异地时另需 mc、openssl
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

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_BACKUP_USER="${MYSQL_BACKUP_USER:-${MYSQL_USERNAME:-root}}"
MYSQL_BACKUP_PASSWORD="${MYSQL_BACKUP_PASSWORD:-${MYSQL_ROOT_PASSWORD:-${MYSQL_PASSWORD:-root}}}"
MYSQL_BINLOG="${MYSQL_BINLOG:-mysqlbinlog}"
BINLOG_DIR="${BINLOG_ARCHIVE_DIR:-$ROOT/backups/binlog}"
RETENTION_DAYS="${BINLOG_SHIP_RETENTION_DAYS:-7}"
MC_TMP=""
TMP=""

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
command -v "$MYSQL_BINLOG" >/dev/null 2>&1 || die "缺少 mysqlbinlog"
mkdir -p "$BINLOG_DIR"
LOCK="$BINLOG_DIR/.ship.lock"
if ! mkdir "$LOCK" 2>/dev/null; then die "已有 binlog ship 在运行：$LOCK"; fi
TMP="$(mktemp -d "$BINLOG_DIR/.tmp.XXXXXX")"
cleanup() {
  local status=$?
  trap - EXIT
  [ -n "$TMP" ] && rm -rf "$TMP"
  [ -n "$MC_TMP" ] && rm -rf "$MC_TMP"
  rmdir "$LOCK" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT

export MYSQL_PWD="$MYSQL_BACKUP_PASSWORD"
MYSQL=(mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_BACKUP_USER")
"${MYSQL[@]}" --batch --skip-column-names -e "SHOW BINARY LOGS" > "$TMP/logs.tsv" 2>"$TMP/list.err" \
  || { cat "$TMP/list.err" >&2 || true; die "SHOW BINARY LOGS 失败（需要 REPLICATION CLIENT/SLAVE，且已开启 binlog）"; }

declare -a CURRENT_LOGS=()
COUNT=0
while IFS=$'\t' read -r name size _rest; do
  [ -n "$name" ] || continue
  CURRENT_LOGS+=("$name")
  size="${size:-0}"
  local_file="$BINLOG_DIR/$name"
  if [ -s "$local_file" ] && [ "$(wc -c < "$local_file" | tr -d ' ')" -ge "$size" ]; then
    continue
  fi
  download_dir="$TMP/download-$COUNT"
  mkdir -p "$download_dir"
  "$MYSQL_BINLOG" --read-from-remote-server --raw --host="$MYSQL_HOST" --port="$MYSQL_PORT" \
    --user="$MYSQL_BACKUP_USER" --result-file="$download_dir/" "$name" \
    >/dev/null 2>>"$TMP/download.err" \
    || { cat "$TMP/download.err" >&2 || true; die "下载 binlog 失败：${name}"; }
  [ -s "$download_dir/$name" ] || die "binlog 下载为空：${name}"
  mv "$download_dir/$name" "$local_file"
  log "已更新 ${name}（$(wc -c < "$local_file" | tr -d ' ') bytes）"
  COUNT=$((COUNT + 1))
done < "$TMP/logs.tsv"

# 删除不再存在于 MySQL 且超过保留期的本地 binlog（不删正在使用的日志）
if [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
  for file in "$BINLOG_DIR"/mysql-bin.*; do
    [ -f "$file" ] || continue
    name="$(basename "$file")"
    keep=0
    for current in "${CURRENT_LOGS[@]}"; do
      [ "$name" = "$current" ] && keep=1 && break
    done
    [ "$keep" = "1" ] && continue
    if [ -n "$(find "$file" -mtime +"$RETENTION_DAYS" -print 2>/dev/null)" ]; then
      rm -f "$file"
      log "清理过期 binlog：${name}"
    fi
  done
fi

# 异地加密上传：仅上传新增/变大的文件
if [ -n "${BINLOG_SHIP_OFFSITE_ENDPOINT:-}" ] || [ -n "${BINLOG_SHIP_OFFSITE_BUCKET:-}" ]; then
  [ -n "${BINLOG_SHIP_OFFSITE_ENDPOINT:-}" ] || die "BINLOG_SHIP_OFFSITE_ENDPOINT 未配置"
  [ -n "${BINLOG_SHIP_OFFSITE_BUCKET:-}" ] || die "BINLOG_SHIP_OFFSITE_BUCKET 未配置"
  [ -n "${BINLOG_SHIP_OFFSITE_ACCESS_KEY:-}" ] || die "BINLOG_SHIP_OFFSITE_ACCESS_KEY 未配置"
  [ -n "${BINLOG_SHIP_OFFSITE_SECRET_KEY:-}" ] || die "BINLOG_SHIP_OFFSITE_SECRET_KEY 未配置"
  [ -n "${BINLOG_SHIP_ENCRYPT_KEY_FILE:-}" ] || die "异地 binlog 要求 BINLOG_SHIP_ENCRYPT_KEY_FILE"
  [ -r "$BINLOG_SHIP_ENCRYPT_KEY_FILE" ] || die "binlog 加密密钥不可读：$BINLOG_SHIP_ENCRYPT_KEY_FILE"
  command -v mc >/dev/null 2>&1 || die "异地 binlog 需要 mc"
  command -v openssl >/dev/null 2>&1 || die "异地 binlog 需要 openssl"
  PREFIX="${BINLOG_SHIP_OFFSITE_PREFIX:-ai-agent-platform/binlog}"
  MC_TMP="$(mktemp -d "${TMPDIR:-/tmp}/aiagent-binlog-mc.XXXXXX")"
  export MC_CONFIG_DIR="$MC_TMP"
  mc alias set offsite "$BINLOG_SHIP_OFFSITE_ENDPOINT" "$BINLOG_SHIP_OFFSITE_ACCESS_KEY" \
    "$BINLOG_SHIP_OFFSITE_SECRET_KEY" >/dev/null 2>&1 || die "异地 mc alias 配置失败"
  mc mb --ignore-existing "offsite/$BINLOG_SHIP_OFFSITE_BUCKET" >/dev/null 2>&1 \
    || die "异地 binlog bucket 不可用"
  STATE="$BINLOG_DIR/.uploaded.tsv"
  touch "$STATE"
  for file in "$BINLOG_DIR"/mysql-bin.*; do
    [ -f "$file" ] || continue
    name="$(basename "$file")"
    size="$(wc -c < "$file" | tr -d ' ')"
    if awk -v n="$name" -v s="$size" '$1==n && $2==s {found=1} END{exit !found}' "$STATE"; then
      continue
    fi
    enc="$TMP/$name.enc"
    openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$BINLOG_SHIP_ENCRYPT_KEY_FILE" \
      -in "$file" -out "$enc"
    object="$PREFIX/$name.enc"
    mc cp "$enc" "offsite/$BINLOG_SHIP_OFFSITE_BUCKET/$object" >/dev/null \
      || die "binlog 上传失败：$object"
    awk -v n="$name" '$1!=n' "$STATE" > "$STATE.tmp" || true
    printf '%s\t%s\n' "$name" "$size" >> "$STATE.tmp"
    mv "$STATE.tmp" "$STATE"
    log "已上传 $object"
  done
fi

log "binlog ship 完成：更新 $COUNT 个文件；目录 $BINLOG_DIR"
