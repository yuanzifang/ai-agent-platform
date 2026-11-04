# 农业业务文档

本目录只存放农业知识问答项目的专用资料，避免与平台通用文档混在一起。

## 内容

- 农业 60 题评测集说明与失败归因
- 农业回答质量基线、失败复测、交叉验证结果
- 农业知识库清单
- 入库清洗与切块规范
- 检索/召回审计
- 本地 Reranker 部署与验证
- Qwen3.8 27B 农业模型切换记录
- 农业在线模型对比

## 相关代码

- `backend/src/main/java/com/platform/extension/agri/`
- `backend/src/main/java/com/platform/knowledge/service/ContentCleaner.java`
- `backend/src/main/java/com/platform/knowledge/service/SectionTypeClassifier.java`
- `scripts/eval-agri-answer-quality.py`
- `scripts/reranker_server.py`
- `eval-data/agri/`：评测输入、历史基线、失败复测原始 JSON

## 当前状态

- Agent：5422
- 在线模型：qwen2.5:7b-chat
- 知识库：913
- 当前版本：V33
- 完整 60 题：44/60，73.3%
