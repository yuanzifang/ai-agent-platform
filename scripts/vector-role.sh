#!/usr/bin/env bash
# 创建/收敛 PG 向量库专用账号，并把 tenant_* Schema 对象所有权转交给该账号。
# 默认 dry-run；确认后加 --apply。执行前必须用数据库管理员连接（PGUSER/PGPASSWORD）。
set -euo pipefail

: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGDATABASE:?set PGDATABASE}"
: "${PGUSER:?set PGUSER (database admin)}"
: "${APP_DB_ROLE:?set APP_DB_ROLE (dedicated vector application role)}"

APPLY=false
if [[ "${1:-}" == "--apply" ]]; then APPLY=true; elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--apply]" >&2
  exit 2
fi
[[ "$APP_DB_ROLE" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "invalid APP_DB_ROLE" >&2; exit 2; }
if [[ "$APPLY" == true ]]; then
  : "${APP_DB_PASSWORD:?set APP_DB_PASSWORD for --apply}"
fi

SQL=$(mktemp)
trap 'rm -f "$SQL"' EXIT
cat > "$SQL" <<'SQL'
\set ON_ERROR_STOP on
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_role', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_role') \gexec
SELECT format('ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', :'app_role') \gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'app_role', :'app_password') \gexec
SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO %I', :'db', :'app_role') \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', nspname, :'app_role')
FROM pg_namespace
WHERE nspname ~ '^tenant_[0-9]+$' \gexec
SELECT format('ALTER TABLE %I.%I OWNER TO %I', schemaname, tablename, :'app_role')
FROM pg_tables
WHERE schemaname ~ '^tenant_[0-9]+$' \gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO %I', schemaname, sequencename, :'app_role')
FROM pg_sequences
WHERE schemaname ~ '^tenant_[0-9]+$' \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', nspname, :'app_role')
FROM pg_namespace
WHERE nspname ~ '^tenant_[0-9]+$' \gexec
SQL

if [[ "$APPLY" == true ]]; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
    -v app_role="$APP_DB_ROLE" -v app_password="$APP_DB_PASSWORD" -v db="$PGDATABASE" \
    -v ON_ERROR_STOP=1 -f "$SQL"
  echo "applied vector role '$APP_DB_ROLE' and tenant object ownership"
else
  echo "[dry-run] SQL uses psql variables; re-run with APP_DB_PASSWORD=... $0 --apply"
  cat "$SQL"
fi
