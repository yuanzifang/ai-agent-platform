# Trace / Span 保留清理

## 配置

```yaml
observability:
  trace:
    retention-enabled: ${TRACE_RETENTION_ENABLED:true}
    retention-days: ${TRACE_RETENTION_DAYS:30}
    cleanup-batch: ${TRACE_CLEANUP_BATCH:1000}
    cleanup-cron: ${TRACE_CLEANUP_CRON:0 50 4 * * *}
```

## 行为

- 默认每天 04:50 执行；
- 截止时间 = 当前时间 - retention-days；
- 先删 `agent_span`，再删 `agent_trace`；
- 按 `created_at < cutoff LIMIT batch` 分批删除，默认 1000 行/批，最多 1000 批；
- 同一时刻只允许一个清理任务；
- 单次失败只记录日志，不影响服务；
- 平台管理员可手动触发：

```http
POST /api/v1/observability/retention/run
```

返回删除的 spans/traces 数量。

## 已验证

- 单测：分批删除计数、禁用跳过；
- 手动接口：`spansDeleted=0 / tracesDeleted=0`（当前无过期数据）。
