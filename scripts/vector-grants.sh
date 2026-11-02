#!/usr/bin/env bash
# PG 向量库最小权限收敛：只授予 tenant_* Schema 的 DML/序列权限。
# 默认 dry-run；确认后加 --apply。必须由数据库 owner/管理员执行。
set -euo pipefail

: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGDATABASE:?set PGDATABASE}"
: "${PGUSER:?set PGUSER}"
: "${APP_DB_ROLE:?set APP_DB_ROLE (tenant vector application role)}"

APPLY=false
if [[ "${1:-}" == "--apply" ]]; then APPLY=true; elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--apply]" >&2; exit 2
fi
[[ "$APP_DB_ROLE" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "invalid APP_DB_ROLE" >&2; exit 2; }

SCHEMAS=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -Atc \
  "SELECT schema_name FROM information_schema.schemata WHERE schema_name ~ '^tenant_[0-9]+$' ORDER BY 1")
[[ -n "$SCHEMAS" ]] || { echo "no tenant_* schemas found"; exit 1; }

SQL=$(mktemp)
trap 'rm -f "$SQL"' EXIT
{
  printf 'GRANT CONNECT ON DATABASE %s TO %s;\n' "$PGDATABASE" "$APP_DB_ROLE"
  while IFS= read -r schema; do
    [[ "$schema" =~ ^tenant_[0-9]+$ ]] || continue
    printf 'GRANT USAGE ON SCHEMA %s TO %s;\n' "$schema" "$APP_DB_ROLE"
    printf 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %s TO %s;\n' "$schema" "$APP_DB_ROLE"
    printf 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %s TO %s;\n' "$schema" "$APP_DB_ROLE"
    printf 'ALTER DEFAULT PRIVILEGES IN SCHEMA %s GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %s;\n' "$schema" "$APP_DB_ROLE"
    printf 'ALTER DEFAULT PRIVILEGES IN SCHEMA %s GRANT USAGE, SELECT ON SEQUENCES TO %s;\n' "$schema" "$APP_DB_ROLE"
  done <<< "$SCHEMAS"
} > "$SQL"

if [[ "$APPLY" == true ]]; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f "$SQL"
  echo "applied grants for $(printf '%s\n' "$SCHEMAS" | wc -l | tr -d ' ') tenant schemas to $APP_DB_ROLE"
else
  echo "[dry-run] SQL written to $SQL; re-run with --apply"
  cat "$SQL"
fi
