#!/usr/bin/env bash
# 大表月分区 DDL 锁表基准（临时表，不触碰业务表）。
# 用法：MYSQL_PASSWORD=root ./scripts/partition-ddl-benchmark.sh
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-ai_agent_platform}"
ROWS="${ROWS:-100000}"
TABLE="_partition_ddl_bench_$$"

args=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USERNAME")
if [[ -n "$MYSQL_PASSWORD" ]]; then args+=("-p$MYSQL_PASSWORD"); fi
args+=("$MYSQL_DATABASE")

q() { mysql "${args[@]}" -N -s -e "$1"; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

cleanup() { q "DROP TABLE IF EXISTS \`$TABLE\`" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

q "DROP TABLE IF EXISTS \`$TABLE\`" >/dev/null
q "CREATE TABLE \`$TABLE\` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  payload VARCHAR(255) NOT NULL,
  PRIMARY KEY (id, created_at)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(created_at) (
  PARTITION p202601 VALUES LESS THAN ('2026-02-01'),
  PARTITION p202602 VALUES LESS THAN ('2026-03-01'),
  PARTITION p202603 VALUES LESS THAN ('2026-04-01'),
  PARTITION p202604 VALUES LESS THAN ('2026-05-01'),
  PARTITION p202605 VALUES LESS THAN ('2026-06-01'),
  PARTITION p202606 VALUES LESS THAN ('2026-07-01'),
  PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
  PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
  PARTITION p202612 VALUES LESS THAN ('2027-01-01'),
  PARTITION pmax VALUES LESS THAN (MAXVALUE)
)"
q "SET SESSION cte_max_recursion_depth = 1000000;
   INSERT INTO \`$TABLE\` (created_at, payload)
   WITH RECURSIVE seq(n) AS (
     SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $ROWS
   )
   SELECT DATE_ADD('2026-01-01', INTERVAL (n % 365) DAY), RPAD('x', 128, 'x') FROM seq" >/dev/null

t0=$(now_ms)
q "ALTER TABLE \`$TABLE\` REORGANIZE PARTITION pmax INTO (
  PARTITION p202701 VALUES LESS THAN ('2027-02-01'),
  PARTITION pmax VALUES LESS THAN (MAXVALUE))"
t1=$(now_ms)

rows_before_drop=$(q "SELECT COUNT(*) FROM \`$TABLE\`")
t2=$(now_ms)
q "ALTER TABLE \`$TABLE\` DROP PARTITION p202601"
t3=$(now_ms)

rows_after_drop=$(q "SELECT COUNT(*) FROM \`$TABLE\`")
parts=$(q "SELECT COUNT(*) FROM information_schema.partitions WHERE table_schema=DATABASE() AND table_name='$TABLE' AND partition_name IS NOT NULL")
echo "table=$TABLE rows_before_drop=$rows_before_drop rows_after_drop=$rows_after_drop partitions=$parts"
echo "ddl_reorganize_pmax_ms=$((t1-t0))"
echo "ddl_drop_partition_ms=$((t3-t2))"
