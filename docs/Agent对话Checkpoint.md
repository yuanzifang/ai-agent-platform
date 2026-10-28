# Agent 对话级 Checkpoint

更新：2026-10-28

## 目的

SSE 生成过程中除了 Redis `chat:live` 快照，再持久化一份数据库 checkpoint，避免 Redis 过期、实例重启后完全丢失最近内容。

## 记录

表 `chat_checkpoint`（按 conversation_id 唯一）：

- conversationId / tenantId / traceId
- status：RUNNING / SUCCESS / STOPPED / ERROR
- 已生成内容
- messageId
- input/output tokens
- citations JSON
- created/updated time

## 写入时机

- 开始生成：空 RUNNING checkpoint
- 生成中：约每 2 秒持久化增量
- 成功、停止、失败：最终 checkpoint

## 读取

查询最近 checkpoint：

```http
GET /api/v1/conversations/{id}/checkpoint
```

从部分 checkpoint 继续生成（非流式、best-effort）：

```http
POST /api/v1/conversations/{id}/checkpoint/continue
```

会基于用户原始问题和已生成部分调用模型继续，新建一条助手消息；不保证模型不重复已有内容，生产环境建议优先使用正常 SSE 续传。

断线续传：

`GET /api/v1/conversations/{id}/resume?offset=...`：

1. 优先使用 Redis live state
2. Redis 不存在时，回退 `chat_checkpoint`
3. 回放 checkpoint 内容并发送 `done`
4. 若 checkpoint 仍是 RUNNING，状态按 `UNKNOWN` 返回，不假装继续生成

## 边界

- Checkpoint 用于断线后回放最近内容，不负责重新调用模型继续生成
- 写入每 2 秒一次，极端情况下最多丢失约 2 秒内容
- 成功后仍保留最近一次 checkpoint，便于审计/回放
