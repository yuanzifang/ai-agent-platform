# 大表月分区 DDL 压测

日期：2026-10-29

## 脚本

```bash
MYSQL_PASSWORD=... ROWS=100000 \
  ./scripts/partition-ddl-benchmark.sh
```

脚本创建临时表 `_partition_ddl_bench_<pid>`，模拟现有月度 RANGE COLUMNS 分区 + `pmax`，执行：

1. 插入 100,000 行
2. `REORGANIZE PARTITION pmax` 增加未来月份分区
3. `DROP PARTITION p202601`
4. 输出行数、分区数和 DDL 耗时
5. 自动删除临时表

不会触碰业务表。

## 本机结果

```text
rows_before_drop=100000
rows_after_drop=91507
partitions=13
ddl_reorganize_pmax_ms=51
ddl_drop_partition_ms=36
```

环境：本机 MySQL 8.x，InnoDB，临时表，无并发写入。该结果是 **DDL 基准**，不是生产锁表 SLA；生产窗口仍需在真实数据量/复制拓扑/并发写入下复测。

## 用途

- 评估 `PartitionMaintenanceService` 自动补 pmax 的 DDL 开销
- 评估月度 `DROP PARTITION` 归档窗口
- 为 2026-01 前 pmax 迁移提供本地基线

## 仍缺

- 并发写入下的锁等待 P95/P99
- 生产规模（数百万行、主从复制）窗口演练
