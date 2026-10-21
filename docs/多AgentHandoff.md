# 多 Agent Handoff 与共享上下文

## 已实现

新增工作流节点类型 `HANDOFF`：

- 选择目标 Agent；
- 通过最新发布快照调用 `ChatService.debugChat`，不是只复用 system prompt；
- 目标 Agent 使用自己的完整配置：模型、工具、技能、知识库、子 Agent、记忆配置；
- `inputTemplate` 支持 `{{input}}` / `{{last}}`；
- 输出作为当前节点结果，进入后续节点的「前序节点结果」共享上下文；
- 后续 LLM/AGENT/HANDOFF 节点可读取前序输出；
- 节点参与重试、onError 策略（和执行器一致）。

这不是完整 Graph 引擎，但补上了真实的 **handoff + 共享上下文**：工作流 DAG 的边仍是 `next`，没有新增任意 fan-out/fan-in 或状态读写节点。

## 验证

2026-10-21 E2E：

- 创建目标 Agent A、源 Agent B；
- B 工作流：START → HANDOFF(A) → LLM → END；
- 运行成功；
- nodeRuns 包含 `HANDOFF`；
- 后续 LLM 的 system prompt 实测包含「前序节点结果」，证明 handoff 输出进入共享上下文；
- 完整 E2E **30/30**，单测 **52/52**。

## 边界

- 不是完整 Graph 引擎：没有显式边列表、fan-out/fan-in、共享状态读写节点、条件路由 DSL；
- `HANDOFF` 目前顺序执行，目标 Agent 输出只进入 outputs/lastOutput；
- 目标 Agent 的内部 Trace 记录为 debug 用量，不生成独立的正式对话 Trace；
- 没有 handoff 回传/多轮往返；
- 并行分支内的 HANDOFF 尚未验证。
