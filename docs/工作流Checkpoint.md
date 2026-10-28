# 工作流 Checkpoint 与恢复

更新：2026-10-28

## 机制

每次工作流节点完成、进入 END、失败，或进入审批/并行审批/循环超限等待时，写入 `workflow_checkpoint`：

- runId / tenantId
- 已完成节点 `nodeId`
- 下一节点 `nextNodeId`
- 状态：`RUNNING` / `SUCCESS` / `FAILED`
- 当前 `outputs` 与共享 `state`
- 输出文本、Token 用量、创建时间

Checkpoint 是增量历史，不覆盖当前 `workflow_run.context_json`。

## 恢复

```http
POST /api/v1/workflow/runs/{runId}/resume
```

仅允许 `FAILED` 运行恢复：

- 从 `workflow_run.currentNode` 继续
- 不重放已经完成的节点
- 保留已有 outputs/state
- 写入审计 `workflow.resume`
- 当前节点为 `TOOL` / `HANDOFF` / `AGENT` 时默认拒绝，需显式传 `allowSideEffects=true`，避免恢复导致外部副作用重复执行

查询：

```http
GET /api/v1/workflow/runs/{runId}/checkpoints
GET /api/v1/workflow/runs/{runId}
```

`runDetail` 的 `checkpoints` 字段返回历史检查点。

## 边界

- 当前只支持失败运行继续，不支持任意历史 checkpoint 回滚
- 已产生外部副作用的节点不建议重复执行；恢复前需人工确认
- 审批暂停仍走原审批流，不使用 FAILED 恢复接口
