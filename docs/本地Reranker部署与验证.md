# 本地 Reranker 部署与验证（方案 B）

日期：2026-11-03

## 1. 部署内容

在 Mac 本机启动 `BAAI/bge-reranker-v2-m3`，通过 HTTP 提供平台标准 rerank 协议：

- 地址：`http://192.168.64.1:8010/rerank`
- 健康检查：`http://192.168.64.1:8010/health`
- 模型：`BAAI/bge-reranker-v2-m3`
- 设备：CPU
- Python：3.11（uv venv）
- 模型缓存：`~/.cache/agri-reranker-hf`

相关脚本：

- `scripts/reranker_server.py`
- `scripts/start-reranker.sh`
- `scripts/smoke-reranker.sh`

启动：

```bash
bash scripts/start-reranker.sh
```

冒烟测试：

```bash
bash scripts/smoke-reranker.sh
```

## 2. 平台配置

已通过 Helm 写入后端环境变量：

```yaml
RERANK_ENABLED: "true"
RERANK_ENDPOINT: "http://192.168.64.1:8010/rerank"
RERANK_MODEL: "bge-reranker-v2-m3"
RERANK_TOP_N: "5"
```

当前后端配置：

```text
RERANK_ENABLED=true
RERANK_ENDPOINT=http://192.168.64.1:8010/rerank
RERANK_MODEL=bge-reranker-v2-m3
```

## 3. 重排语义

- Agent 知识库 `retrievalConfig.rerank=true` 才启用；
- 默认是 **关闭**，避免平台开启全局 reranker 后影响所有 Agent；
- 工作流 `KNOWLEDGE` 节点同样需要显式开启；
- `rerankTopN` 控制重排后保留条数；
- 未配置 `RERANK_ENDPOINT` 或 `RERANK_ENABLED=false` 时，开关不生效。

## 4. 验证结果

### 4.1 本地服务

`smoke-reranker.sh` 返回正常：

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "results": [
    { "index": 1, "relevance_score": 0.5034 },
    { "index": 2, "relevance_score": 0.2405 },
    { "index": 0, "relevance_score": 0.0981 }
  ]
}
```

3 个候选片段耗时约 **0.16 秒**（模型已加载、CPU）。

### 4.2 平台链路

用 Agent 调试接口对比同一问题：

- `rerank=false`：4 条引用
- `rerank=true`：3 条引用，顺序发生变化
- 后端日志出现 `POST /rerank 200`
- 引用顺序按 reranker 返回值变化，说明链路已打通

### 4.3 质量影响

第一轮 topK=3、候选中只有 3 条的重排复测：

- 无重排：7/15（V13，单次 2.5 裁判）
- 开启重排：5/15（V21，单次 2.5 裁判）

随后改为“先召回更大候选池，再重排到 topK”：

- 15 道历史失败题：8/15（V23）
- 完整 60 题：33/60，平均分 0.7492（V23）
- 无重排完整 60 题：34/60（V13）

重排对 15 道失败题有小幅提升，但完整 60 题没有超过无重排基线，且会改变引用顺序和条数。为避免当前 7B 答案质量波动，**Agent 5422 暂未开启 rerank**，保持 V24 的关闭状态；平台服务保持运行，后续可针对具体 Agent 做 A/B 后再开启。

V23 原始结果：`eval-data/agri/农业回答质量基线-V23-60题.json`。

### 4.4 本次改动

- `HybridRetrievalService` 在 `rerank=true` 时先把候选池扩大到 `max(topK*3, 10)`，再截断到 `rerankTopN`；
- 支持 `rerankScoreThreshold`，过滤低分重排结果；
- 前端 Agent 知识库参数增加“重排最低分”；
- `rerank` 改为显式 opt-in，默认关闭，避免全局开启影响其他 Agent。

## 5. 当前状态

- 本地 reranker：运行中
- 平台 `RERANK_ENABLED=true`
- Agent 5422：V24，`rerank=false`
- 其他 Agent：未显式开启
- 这样不会影响 Agent 4878 等无关业务
- 后端镜像：`aap/backend:agri-rerank2-11031025`
- 前端镜像：`aap/frontend:agri-rerank2-ui-11031026`

## 6. 注意事项

- 本服务运行在 Mac 用户会话中，Mac 重启/休眠后需要重新启动；
- 当前是 CPU 推理；如需生产，应部署为独立常驻服务或容器；
- 如果后续换用 Jina/Cohere 或其他 reranker，只需改 `RERANK_*` 环境变量；
- 真实质量提升需要同时优化候选池；当前是 topK=3 后再重排，候选不足时重排空间有限。
- 本地服务当前没有鉴权，只适合内网/POC；生产需要加 API Key、防火墙或部署到受控集群。
