# Agent 级记忆与多轮自我纠正

## 1. Agent 级自动记忆

`memoryConfig` 新增：

```json
{"enabled": true, "windowSize": 10, "autoExtract": true}
```

- `enabled=false`：不注入历史记忆窗口；
- `autoExtract=false`：本 Agent 对话结束后不自动抽取长期记忆；
- 全局 `memory.auto-extract.enabled` 仍是总开关，Agent 开关只能进一步关闭。

后端在 `ChatStreamService` 调用 `MemoryExtractionService.extractAsync` 前检查 `memoryAutoExtractEnabled(config)`。

## 2. 多轮自我纠正

`agentCapabilities` 新增：

```json
{"selfCorrection": true, "selfCorrectionRounds": 2}
```

- `selfCorrectionRounds` 取值 1~3，默认 1；
- 每轮审查仍由 `SelfCorrectionService` 执行；
- 只有 `score < threshold` 且 revised 与当前回答不同才继续；
- 只下发一次最终 `revision` SSE 事件；
- 自动沉淀坏例时 `actual=原始回答`，`expected=最终修订`；
- 每轮审查用量单独计费，source=self-correction。

前端 Agent 编辑页：

- 能力面板新增审查轮数（1~3，开关关闭时禁用）；
- 记忆面板新增“自动抽取”开关。

## 3. 验证与边界

- 默认 1 轮，保持现有 E2E 行为；
- 完整 E2E 仍覆盖自我纠正单轮；多轮未使用真实模型做质量验收；
- Agent 级开关未做专门的异步抽取延迟 E2E；
- `selfCorrectionRounds` 只影响审查轮数，不改变阈值。
