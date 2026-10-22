# 多 Agent Handoff 与共享上下文

## 已实现

新增工作流节点类型 `HANDOFF`：

- 选择目标 Agent；
- 通过最新发布快照调用 `ChatService.debugChat`，不是只复用 system prompt；
- 目标 Agent 使用自己的完整配置：模型、工具、技能、知识库、子 Agent、记忆配置；
- `inputTemplate` 支持 `{{input}}` / `{{last}}`；
- 输出作为当前节点结果，进入后续节点的「前序节点结果」共享上下文；
- 新增 `STATE` 节点：`stateKey` + `valueTemplate`（支持 `{{input}}`/`{{last}}`/`{{state.key}}`）写入运行级共享状态；
- 后续 LLM/AGENT/HANDOFF 节点可读取前序输出和 `{{state.key}}`；
- 节点参与重试、onError 策略（和执行器一致）。

这不是完整 Graph 引擎，但补上了真实的 **handoff + 持久化共享状态**：工作流 DAG 的边仍是 `next`，并有 STATE 节点读写运行级状态。

## 验证

2026-10-21 E2E：

- 创建目标 Agent A、源 Agent B；
- B 工作流：START → HANDOFF(A) → STATE(handoff_result) → LLM → END；
- 运行成功；
- nodeRuns 包含 `HANDOFF`，run detail `state.handoff_result` 非空；
- 后续 LLM 的 system prompt 实测包含「前序节点结果」和「共享状态」；
- 新增并行 State E2E：两个分支分别写 `branch_a=alpha`、`branch_b=beta`，JOIN 后 run.state 同时保留；
- 完整 E2E **33/33**，单测 **52/52**；
- 并行 HANDOFF E2E：PARALLEL → 两个 HANDOFF 目标 Agent → 两个 STATE → JOIN，`run.state.agent_a/agent_b` 均非空。

## 边界

- 不是完整 Graph 引擎：没有独立边列表、任意 fan-out/fan-in、条件路由 DSL；
- 并行分支内 `STATE` 已支持，并在 JOIN 前合并到运行级 state；
- 并行分支内 `HANDOFF` 已验证 fan-out/fan-in：两个目标 Agent 并行交接，结果分别写 state 并合并；
- 嵌套 PARALLEL 已验证：外层并行分支内再起内层并行，State 逐层合并；
- `HANDOFF` 目前顺序执行；
- 目标 Agent 的内部 Trace 记录为 debug 用量，不生成独立的正式对话 Trace；
- 没有 handoff 回传/多轮往返；
- 并行分支内的 HANDOFF 尚未验证。
