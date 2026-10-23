# 工作流 Graph 有向边

## 1. 定义

工作流定义可在 `nodes` 之外提供 `edges`：

```json
{
  "nodes": [
    { "id": "start", "type": "START", "name": "开始" },
    { "id": "llm1", "type": "LLM", "name": "生成", "prompt": "..." },
    { "id": "end", "type": "END", "name": "结束" }
  ],
  "edges": [
    { "from": "start", "to": "llm1" },
    { "from": "llm1", "to": "end" }
  ]
}
```

- 保存与运行前，服务端把边归一化为节点的 `next`/`trueNext`/`falseNext`，兼容既有执行器；
- 普通节点只允许一条出边；
- `CONDITION` 节点必须有 `label=true` 与 `label=false` 两条出边；
- `PARALLEL` 分支仍由 `branches/joinNode` 表达，边不覆盖分支配置；
- `END` 不允许出边，边数上限 100。

## 2. 前端

Agent 编辑 → 工作流 → 「Graph 有向边（可选，优先于节点 next）」：

- 添加/删除边，选择起点、终点；画布「连线模式」支持点击起点/终点直接创建边；
- 条件节点自动显示 true/false 标签选择；
- 保存时随工作流定义一起提交。

## 3. 已验证

- E2E：节点不配置 `next`，仅配置 `edges`，工作流成功执行且包含 LLM 节点；
- 完整 E2E 36/36；
- 未做独立可视化 Graph 编辑器/任意 DAG 布局；画布仍按归一化后的跳转绘制。
