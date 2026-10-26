#!/usr/bin/env bash
# Vector p95/p99 benchmark against pgvector. Uses the running aap-alert-pgvector-1 container.
# Usage: ROWS=100000 CLIENTS="8 16 32" SECONDS=30 scripts/vector-p99-benchmark.sh
set -euo pipefail

CONTAINER="${CONTAINER:-aap-alert-pgvector-1}"
ROWS="${ROWS:-100000}"
DIMENSIONS="${DIMENSIONS:-1024}"
CLIENTS="${CLIENTS:-8 16 32}"
DURATION="${DURATION:-30}"
SCHEMA="${SCHEMA:-bench}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PGUSER="$(docker exec "$CONTAINER" printenv POSTGRES_USER)"
PGDATABASE="$(docker exec "$CONTAINER" printenv POSTGRES_DB)"
PGPASSWORD="$(docker exec "$CONTAINER" printenv POSTGRES_PASSWORD)"
PSQL=(docker exec -e PGPASSWORD="$PGPASSWORD" -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDATABASE")

echo "vector benchmark: rows=$ROWS dims=$DIMENSIONS container=$CONTAINER"
"${PSQL[@]}" <<SQL
DROP SCHEMA IF EXISTS "$SCHEMA" CASCADE;
CREATE SCHEMA "$SCHEMA";
CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;
CREATE TABLE "$SCHEMA".vectors (
    id BIGSERIAL PRIMARY KEY,
    embedding vector($DIMENSIONS) NOT NULL
);
INSERT INTO "$SCHEMA".vectors (embedding)
SELECT ARRAY(SELECT (random() * 2 - 1)::real FROM generate_series(1, $DIMENSIONS))::vector
FROM generate_series(1, $ROWS);
SET maintenance_work_mem = '512MB';
CREATE INDEX vectors_hnsw ON "$SCHEMA".vectors USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
ANALYZE "$SCHEMA".vectors;
SQL

cat > "$WORK/query.sql" <<SQL
SELECT id FROM ${SCHEMA}.vectors
ORDER BY embedding <=> (SELECT embedding FROM ${SCHEMA}.vectors WHERE id = 1)
LIMIT 5;
SQL

for C in $CLIENTS; do
  PREFIX="/tmp/vector-p99-${ROWS}-${C}"
  docker exec "$CONTAINER" rm -f "${PREFIX}"* || true
  echo "--- clients=$C duration=${DURATION}s ---"
  docker exec -i "$CONTAINER" pgbench -n -U "$PGUSER" \
    -c "$C" -j "$C" -T "$DURATION" \
    -f /dev/stdin -l --log-prefix="$PREFIX" "$PGDATABASE" < "$WORK/query.sql" | \
    grep -E "number of transactions|latency average|tps|failed"
  # pgbench log: client_id transaction_no time script_no epoch latency_us
  docker exec "$CONTAINER" sh -c "cat ${PREFIX}*" 2>/dev/null | \
    awk '{print $3}' | sort -n | \
    python3 -c 'import sys
xs=[int(x) for x in sys.stdin if x.strip()]
if xs:
  q=lambda p: xs[min(len(xs)-1, int(len(xs)*p))]
  print(f"  samples={len(xs)} p50={q(.50)/1000:.2f}ms p95={q(.95)/1000:.2f}ms p99={q(.99)/1000:.2f}ms max={xs[-1]/1000:.2f}ms")'
done
