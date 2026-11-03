# Agent 扩展与业务解耦

更新日期：2026-11-03

## 目标

平台核心只保留通用对话、检索和工具编排能力，不按行业写分支。行业规则通过
Agent 配置启用扩展实现，扩展代码放在 `com.platform.extension.*`，核心服务只依赖
SPI 接口。

## 核心扩展点

| 扩展点 | 位置 | 用途 |
|---|---|---|
| `AgentRequestGuard` | `com.platform.extension.agent` | 模型/RAG 调用前的请求护栏 |
| `RetrievalPolicy` | `com.platform.extension.retrieval` | 查询归一化、业务范围过滤等检索策略 |
| `ImageUnderstandingService` 提示词 | Agent 配置 | 业务图片识别提示词 |

核心类 `ChatStreamService`、`HybridRetrievalService`、`ImageUnderstandingService`
不引用任何行业扩展实现；行业实现位于 `com.platform.extension.agri`。

## Agent 配置

### 1. 请求护栏

在 Agent 的 `agentCapabilities` 中加入扩展 ID：

```json
{
  "agentCapabilities": {
    "extensions": ["agri-safety"]
  }
}
```

未出现在列表中的 Agent 不执行该护栏。也可写成逗号分隔字符串，便于脚本写入。

### 2. 检索策略

农业检索策略只在对应知识库绑定配置显式开启时生效：

```json
{
  "retrievalConfig": {
    "normalizeQuery": true,
    "cropScope": true
  }
}
```

`normalizeQuery` 去掉问句套话；`cropScope` 在问题已明确作物时优先保留命中该
作物的片段，没有命中时回退原结果。其他 Agent 不设置这两个开关即可保持默认检索。

### 3. 图片识别提示词

在 Agent 的 `agentCapabilities` 中加入业务提示词；未配置时使用平台通用提示：

```json
{
  "agentCapabilities": {
    "imageUnderstandingPrompt": "你是农业植物保护与作物栽培专家……"
  }
}
```

### 4. 通过 API 写入

`agentCapabilities` 是 Agent 更新接口的正常字段。更新前先读取当前配置，合并后再
提交，避免覆盖其它能力开关：

```bash
# 示例：只演示字段位置；实际脚本应先 GET /api/v1/agent/{id} 并合并现有 capabilities
curl -X PUT "$BASE/api/v1/agent/5422" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"agentCapabilities":{"extensions":["agri-safety"],
       "imageUnderstandingPrompt":"你是农业植物保护与作物栽培专家……"}}'
```

知识库绑定里的 `retrievalConfig` 使用同一个 Agent 更新接口的 `knowledgeBases`
数组写入。控制台未提供这两个高级字段的专用表单项时，应走 API 或部署数据迁移，
不要把它们写回核心代码。

## 兼容回填

迁移 `V132__agent_runtime_extensions.sql` 只对现有 `category` 含“农业”的 Agent
写入 `agri-safety` 扩展和业务图片提示词。这是 Agent 配置数据回填，不是核心代码
分支；需要停用时从 `agentCapabilities.extensions` 移除该 ID，并清理
`imageUnderstandingPrompt` 即可。

## 生成评测数据

农业评测集、基线、失败复测和模型对比 JSON 统一放在 `eval-data/agri/`，不再放在
`docs/`。`eval-data/README.md` 说明其生成数据属性和复跑脚本。
