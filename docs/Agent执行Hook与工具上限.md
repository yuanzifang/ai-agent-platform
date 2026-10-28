# Agent 执行 Hook 与工具调用上限

更新：2026-10-28

## Hook 设计

`AgentHook` 是工具调用生命周期的扩展点：

```java
Decision beforeTool(ToolContext context);
void afterTool(ToolContext context, String output, String status);
```

所有工具回调在 `ToolRuntimeService` 装配完成后统一包装为 `HookedToolCallback`，因此 API、MCP、技能、内置工具和子 Agent 都经过同一条 Hook 链。

Hook 异常默认记录日志后放行，避免扩展点故障影响主链路；安全策略需要 fail-closed 时应显式在 Hook 内实现。

## 已实现：单轮工具调用上限

配置：

```env
AGENT_TOOL_MAX_CALLS_PER_TURN=0
```

- `0`：不限制，保持原行为
- `>0`：同一轮对话最多执行该数量的工具调用
- 超过上限返回：

```json
{
  "ok": false,
  "error": "TOOL_CALL_LIMIT",
  "retryable": false,
  "message": "本轮工具调用次数已达上限，请基于已有结果回答或询问用户"
}
```

拒绝调用不会进入真实工具执行，也不会触发下游 HTTP/MCP/脚本。

## 后续可接入的 Hook 策略

- 上下文压缩/编辑
- 模型调用次数上限
- 工具重试策略
- 动态工具选择
- 审批/中断
- 工具结果脱敏
- 调用审计与指标

当前只落地了工具调用上限，其余作为后续可插拔策略。
