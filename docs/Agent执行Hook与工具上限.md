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

配置已提升到 **Agent 编辑页 → 能力**：

- `单轮工具调用上限`：0 表示不限制
- `工具输出脱敏`：默认开启
- `工具输出字符上限`：0 表示沿用工具自身/全局限制

全局兜底仍支持环境变量：

```env
AGENT_TOOL_MAX_CALLS_PER_TURN=0
AGENT_HOOK_REDACT_SECRETS_ENABLED=true
```

优先级：Agent 的 `agentCapabilities.maxToolCalls` / `agentCapabilities.redactSecrets` 覆盖全局默认值。
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

## 已实现：工具输出敏感信息脱敏

`SecretRedactionHook` 默认开启，在工具输出回给模型前脱敏：

- OpenAI/DeepSeek 风格 `sk-...`
- GitHub token `ghp_...`
- AWS access key `AKIA...`
- `Bearer ...`
- JWT
- PEM 私钥

Agent 页面可单独关闭该 Agent 的工具输出脱敏，并可配置最多 20 条自定义脱敏正则（JSON 数组）；全局默认由 `AGENT_HOOK_REDACT_SECRETS_ENABLED` 控制。非法正则自动跳过，不影响内置规则。

当前已落地工具调用上限和敏感信息脱敏，其余作为后续可插拔策略。
