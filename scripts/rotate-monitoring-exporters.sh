#!/usr/bin/env bash
# 轮换 MySQL/PostgreSQL/Redis exporter 只读账号密码。
# 默认 dry-run；必须显式 --confirm 才执行。执行前备份 .env。
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

CONFIRM="${1:-}"
if [ "$CONFIRM" != "--confirm" ]; then
  echo "DRY-RUN：将轮换 MYSQL_EXPORTER_PASSWORD / PG_EXPORTER_PASSWORD / REDIS_EXPORTER_PASSWORD"
  echo "并更新 MySQL/PostgreSQL/Redis 只读账号及本机 exporter 容器。"
  echo "确认执行：scripts/rotate-monitoring-exporters.sh --confirm"
  exit 0
fi

for v in MYSQL_ROOT_PASSWORD MYSQL_EXPORTER_PASSWORD PG_EXPORTER_PASSWORD REDIS_EXPORTER_PASSWORD; do
  eval "test -n \"\${$v:-}\"" || { echo "缺少 $v" >&2; exit 1; }
done

BACKUP=".env.bak.$(date +%Y%m%d-%H%M%S)"
cp .env "$BACKUP"; chmod 600 "$BACKUP"
echo "已备份 .env -> $BACKUP"

python3 - <<'PY'
from pathlib import Path
import secrets
p=Path('.env'); lines=p.read_text().splitlines(); new={
 'MYSQL_EXPORTER_PASSWORD':secrets.token_hex(16),
 'PG_EXPORTER_PASSWORD':secrets.token_hex(16),
 'REDIS_EXPORTER_PASSWORD':secrets.token_hex(16),
}; seen=set(); out=[]
for line in lines:
    k=line.split('=',1)[0] if '=' in line else ''
    if k in new: out.append(f'{k}={new[k]}'); seen.add(k)
    else: out.append(line)
for k,v in new.items():
    if k not in seen: out.append(f'{k}={v}')
p.write_text('\n'.join(out)+'\n')
PY
chmod 600 .env
set -a; . ./.env; set +a

echo "→ 更新 MySQL exporter 用户"
mysql -h 127.0.0.1 -P 3306 -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "ALTER USER 'exporter'@'%' IDENTIFIED BY '$MYSQL_EXPORTER_PASSWORD';" >/dev/null

echo "→ 更新 PostgreSQL exporter 用户"
psql -h 127.0.0.1 -p 5432 -U "${PG_ADMIN_USER:-wangpan}" -d vector -v ON_ERROR_STOP=1 \
  -c "ALTER ROLE exporter WITH LOGIN PASSWORD '$PG_EXPORTER_PASSWORD';" >/dev/null

echo "→ 更新 Redis exporter ACL"
redis-cli -h 127.0.0.1 -p 6379 ACL SETUSER exporter on ">$REDIS_EXPORTER_PASSWORD" \
  '~*' '+@read' '+@connection' '+info' '+client' '+config|get' '+slowlog' '+memory' '+latency' '+command' '+dbsize' >/dev/null

echo "→ 重建本地 exporter 容器"
docker rm -f aap-mysqld-exporter-local aap-redis-exporter-local aap-postgres-exporter-local >/dev/null 2>&1 || true
docker run -d --name aap-mysqld-exporter-local --network aap-obs -p 127.0.0.1:19104:9104 \
  -e MYSQLD_EXPORTER_PASSWORD="$MYSQL_EXPORTER_PASSWORD" \
  prom/mysqld-exporter:v0.16.0 --mysqld.address=host.docker.internal:13306 --mysqld.username=exporter >/dev/null
docker run -d --name aap-redis-exporter-local --network aap-obs -p 127.0.0.1:19121:9121 \
  -e REDIS_ADDR=host.docker.internal:16379 -e REDIS_USER=exporter \
  -e REDIS_PASSWORD="$REDIS_EXPORTER_PASSWORD" \
  oliver006/redis_exporter:v1.67.0 >/dev/null
docker run -d --name aap-postgres-exporter-local --network aap-obs -p 127.0.0.1:19187:9187 \
  -e DATA_SOURCE_NAME="postgresql://exporter:$PG_EXPORTER_PASSWORD@host.docker.internal:15432/vector?sslmode=disable" \
  prometheuscommunity/postgres-exporter:v0.17.1 >/dev/null

sleep 10
for m in mysql_up redis_up pg_up; do
  value=$(curl -s "http://127.0.0.1:9090/api/v1/query?query=$m" \
    | python3 -c 'import sys,json; print(",".join(x.get("value",["",""])[1] for x in (json.load(sys.stdin).get("data") or {}).get("result",[])))' 2>/dev/null || true)
  echo "$m=$value"
done
echo "✔ exporter 密码轮换完成；.env 备份：$BACKUP"
