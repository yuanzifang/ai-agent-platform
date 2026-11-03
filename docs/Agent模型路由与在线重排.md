# Agent 模型路由与在线重排

日期：2026-11-03

## 1. Agent 级模型路由

### 配置位置

Agent 编辑页 → 模型选择 → 智能路由。

持久化字段：

```json
{
  "modelConfig": {
    "modelName": "qwen2.5:7b-chat",
    "routing": {
      "enabled": true,
      "classifierModel": "qwen2.5:7b-chat",
      "simpleModel": "qwen2.5:7b-chat",
      "complexModel": "qwen3.8:27b",
      "rules": [
        { "match": ["代码", "编程"], "model": "qwen3.8:27b", "intent": "keyword" }
      ]
    }
  }
}
```

### 运行时优先级

1. 灰度模型命中：`X-Gray-Tag` 匹配时优先灰度模型。
2. 关键词规则命中：直接切到规则指定模型，零额外分类延迟。
3. 配置了 `classifierModel` 时，用分类器判断简单/复杂：
   - `simple` → `simpleModel`
   - `complex` → `complexModel`
4. 分类器超时 1.5 秒、异常或返回不可解析：fail-open，保持原模型。
5. 分类结果缓存 30 分钟，缓存键包含租户、分类器模型和用户消息。

实现位置：

- `backend/src/main/java/com/platform/conversation/service/ModelRoutingService.java`
- `backend/src/main/java/com/platform/conversation/service/ChatStreamService.java`
- `backend/src/main/java/com/platform/agent/service/ChatService.java`（调试对话同样生效）
- `frontend/src/views/AgentEditView.vue`
- `frontend/src/views/agentEditorActions.ts`

Trace 中会记录：

```text
spanType=model, name=smart-routing
attributes: { from, to, reason }
```

reason 示例：

- `rule:keyword`
- `complexity:simple`
- `complexity:complex`

## 2. Agent 知识库在线重排

Agent 编辑页 → 知识库 → 每个知识库参数行增加“重排”开关。

持久化字段：

```json
{
  "knowledgeBases": [
    {
      "knowledgeBaseId": 913,
      "enabled": true,
      "retrievalConfig": {
        "topK": 3,
        "scoreThreshold": 0.3,
        "mode": "hybrid",
        "rerank": true
      }
    }
  ]
}
```

运行时在 `HybridRetrievalService` 召回后调用 `RerankService`；`rerank=false` 时跳过。

### 平台级前置条件

重排服务是全局 HTTP 协议插件，当前默认关闭：

```yaml
retrieval:
  rerank:
    enabled: ${RERANK_ENABLED:false}
    endpoint: ${RERANK_ENDPOINT:}
    api-key: ${RERANK_API_KEY:}
    model: ${RERANK_MODEL:}
    top-n: ${RERANK_TOP_N:5}
```

因此：

- Agent 开关只控制“本 Agent 是否请求重排”；
- 平台没有配置 `RERANK_ENDPOINT` 时，即使开关打开也不会生效；
- 当前本地环境未配置重排服务，所以开关默认不会改变检索结果。

## 3. 工作流 KNOWLEDGE 节点

工作流 `KNOWLEDGE` 节点增加：

- “启用重排”复选框
- “重排保留数” `rerankTopN`

节点字段：

```json
{
  "type": "KNOWLEDGE",
  "knowledgeBaseId": 913,
  "mode": "hybrid",
  "topK": 5,
  "rerank": true,
  "rerankTopN": 5,
  "queryTemplate": "{{input}}"
}
```

工作流仍不支持独立的 `RERANK` 节点；重排作为检索后的处理步骤，放在 `KNOWLEDGE` 节点中更自然。

## 4. 当前状态

- Agent 级模型路由：已接入主对话/流式对话/调试对话，前端配置已上线。
- Agent 5422：当前未启用 routing，保持 V15 的 7B 单模型配置（已发布 V17 清理测试配置）。
- 在线重排：代码和 UI 已接入；本地 BGE reranker 已部署，平台 `RERANK_ENABLED=true`，但 Agent 5422 为 `rerank=false`（15 题复测未看到稳定提升，见 `docs/本地Reranker部署与验证.md`）。
- `RERANK` 作为评测步骤仍保留在评测流程中。

## 5. 验证记录

### 模型路由

- 调试对话规则命中：`路由测试` → `qwen2.5:7b`，返回 `model=qwen2.5:7b`。
- 线上主链路临时切换验证（V18，验证后已清理）：
  - 规则：`切换验证` → `qwen3:8b`
  - Trace `smart-routing`：`from=qwen2.5:7b-chat`，`to=qwen3:8b`，`reason=rule:keyword`
  - 实际 LLM Span：`qwen3:8b`
  - 验证后发布 V19，移除测试 routing，线上恢复 7B 单模型。
- 后端单测：
  - `ModelRoutingServiceTest`
  - `ChatStreamModelRoutingTest`（规则结果、无决策保持原模型、灰度优先）
  - `ChatServiceKnowledgeTest`
  - `AgentServiceVersionTest#updateRejectsPlatformModelInRoutingConfig`（路由模型不能使用 PLATFORM 模型）
  - `HybridRetrievalServiceTest`
  - `AgriSafetyGuardTest`

### 在线重排

- 前端 `agentRoutingConfig.spec.ts`：3 个用例通过，覆盖路由规则序列化、关闭状态、重排默认值与显式关闭。
- `HybridRetrievalServiceTest#rerankTopNComesFromRetrievalConfig`：验证 `rerankTopN` 传入 RerankService。
- `HybridRetrievalServiceTest#rerankCanBeDisabledPerRequest`：验证 `rerank=false` 不调用 RerankService。
- `RerankServiceTest#reordersByServerIndex`：真实启动本地 HTTP stub，验证按 reranker 返回的 index 重排。
- 本地 BGE reranker 已部署到 `http://192.168.64.1:8010/rerank`，平台链路已验证会实际发出 `POST /rerank` 并按返回顺序调整引用。
- 15 道失败题重排复测为 5/15，未超过无重排的 7/15；因此 Agent 5422 暂不开启 rerank，服务保留供后续 A/B。

### 部署

- 前端构建通过，部署镜像 `aap/frontend:agri-rerank-final-11031015`。
- 后端镜像：`aap/backend:agri-rerank-final-11031014`。
- 当前 Agent 5422：V22，未启用 routing；知识库显式 `rerank=false`；测试配置已清理。
