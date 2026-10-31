# 本地模型 E2E 的 CI 手动触发

本地全分支 E2E 需要真实 Ollama 和约 4.7GB 的 `qwen2.5:7b`，默认不随 PR 运行，避免每次 CI 拉模型。

## 触发方式

GitHub Actions → `CI` → `Run workflow`：

```text
local_model = true
```

未勾选时：

- 普通 Chrome E2E 正常执行
- `workflow-all-cases.spec.ts` 自动跳过
- 不安装 Ollama、不拉模型

勾选后额外执行：

```text
安装 Ollama
→ 拉取 qwen2.5:7b（有模型缓存时复用）
→ 注册 OLLAMA Provider + PLATFORM 模型
→ 运行 workflow-all-cases.spec.ts
```

## 本地等价命令

```bash
# 1. 确保 Ollama 有模型
ollama pull qwen2.5:7b

# 2. 注册 Provider/模型
python3 scripts/setup-local-ci-model.py

# 3. 运行本地全分支 E2E
cd frontend
E2E_LOCAL_WORKFLOW=1 ./node_modules/.bin/playwright test e2e/workflow-all-cases.spec.ts
```

## 说明

- 本地模型注册脚本只创建/复用 `providerType=OLLAMA` 的 Provider 和 `qwen2.5:7b` 的 `PLATFORM` 模型。
- 工作流节点显式使用 `qwen2.5:7b`，不依赖 Agent 默认模型。
- CI 手动任务是可选任务，不改变默认 PR E2E 的 63 通过 / 7 跳过基线。
- 真实模型任务耗时会明显高于 Mock E2E，建议只在需要验证本地模型分支时手动触发。
