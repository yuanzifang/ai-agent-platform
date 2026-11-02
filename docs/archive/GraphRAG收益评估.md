# GraphRAG 收益对照评测

日期：2026-10-31

## 目标

回答两个问题，而不是默认宣称 GraphRAG 有收益：

1. 同一知识库、同一模型、同一组问题，GraphRAG 是否提高引用命中/答案质量？
2. 代价是多少：额外延迟、注入上下文长度、图谱命中数？

## 方法

固定以下变量：

- 同一知识库 ID
- 同一模型和 temperature
- 同一 topK / 阈值 / hybrid 模式
- 同一组问题
- 唯一变化：`retrievalConfig.graphEnabled` true / false

不使用 Mock。脚本通过 `/agent/debug/chat` 执行，不创建/修改 Agent、知识库或文档。

## 运行

```bash
# 对照组
python3 scripts/eval-graphrag.py \
  --kb-id 161 --graph off \
  --query "退款一般多久到账" \
  --query "退款需要哪些材料" > /tmp/graph-off.json

# 实验组
python3 scripts/eval-graphrag.py \
  --kb-id 161 --graph on \
  --query "退款一般多久到账" \
  --query "退款需要哪些材料" > /tmp/graph-on.json
```

也可以：

```bash
python3 scripts/eval-graphrag.py --kb-id 161 --graph on --queries-file queries.json
```

输出：

```text
avgLatencyMs / p95LatencyMs
avgCitations
graphHits
每条 query 的 answer / citations / graphHits
```

## 验收建议

至少准备 10–20 个真实问题和带标注的相关分段，比较：

- 命中率 / MRR / 引用正确性
- 答案人工或裁判评分
- P95 延迟增量
- 图谱命中是否真正贡献了相关引用，而不是只增加上下文

只有在质量有提升且延迟可接受时才重新开启知识库 GraphRAG。默认冻结状态不得因为“功能存在”而恢复。

## 当前状态

已提供可重复脚本和方法；真实评测依赖启用图谱的知识库、真实/本地模型和问题标注，当前未伪造结果。
