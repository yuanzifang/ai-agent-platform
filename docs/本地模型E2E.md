# 本地模型 E2E Smoke

日期：2026-10-29

## 目标

用本地 Ollama 模型验证真实链路，不调用云端：

- 计算器工具调用
- 可选知识库 RAG + citation

## 运行

```bash
# 需要本地 Ollama 已有 qwen2.5:7b
python3 scripts/local-model-smoke.py

# 指定知识库
LOCAL_SMOKE_KB_ID=161 python3 scripts/local-model-smoke.py

# 只验证工具
python3 scripts/local-model-smoke.py --skip-rag
```

脚本会：

1. 临时创建 `qwen2.5:7b-chat` Ollama alias
2. 在平台登记为 CHAT 模型
3. 创建带计算器工具的临时 Agent，验证 `123+456=579`
4. 若存在含文档的知识库，创建 RAG Agent 并验证 citation
5. 删除临时 Agent/会话和 alias

## 实测

```text
TOOL PASS answer=计算结果是 579。
RAG PASS kb=591 citations=1 answer=当前提供的信息是关于退款政策的描述...
LOCAL-MODEL-SMOKE PASS
```

## 说明

- 这是集成 smoke，不是模型能力评测
- 8B 级模型工具调用/RAG 质量只代表本地代跑
- 生产验收仍需目标模型、真实知识库和生产并发
