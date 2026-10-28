# Checkpoint 保留策略

更新：2026-10-28

## 配置

```env
CHECKPOINT_RETENTION_ENABLED=true
CHECKPOINT_RETENTION_DAYS=30
CHECKPOINT_RETENTION_BATCH=1000
CHECKPOINT_RETENTION_SCAN_MS=21600000
```

默认每 6 小时清理一次。

## 规则

- `workflow_checkpoint`：删除过期记录，但每个 run 永远保留最新一条
- `chat_checkpoint`：删除更新时间超过保留期的对话 checkpoint
- 单次最多删除 batch 条，避免大事务
- 清理失败只记录日志，不影响在线请求

## 边界

- 保留期过短会降低历史回放能力
- 清理不删除 `workflow_run` / 对话消息本身
