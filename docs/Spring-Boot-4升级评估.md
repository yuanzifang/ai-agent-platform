# Spring Boot 4 / Spring Framework 7 升级评估

日期：2026-11-04

## 结论

**当前不建议直接升级。** 保留 Spring Boot 3.5.16 + Spring Framework 6.2.19，继续用已记录的 VEX 例外控制 backend 镜像扫描；把 Boot 4 / Spring AI 2 作为独立迁移项目排期。

升级不是改一个版本号：

- Spring Boot 4.1.2 使用 Spring Framework 7.0.10；
- Spring AI 2.x 重写了 OpenAI/Anthropic 客户端接入；
- MyBatis-Plus 需要 Boot 4 starter + 独立 JSqlParser 模块；
- Springdoc 需要 3.x；
- Spring Retry 需要显式引入。

## 已实测的路径

### 路径 A：Spring Boot 4.1.2 + Spring AI 2.0.2

修改：

- `spring-boot-starter-parent` → `4.1.2`
- `spring-ai.version` → `2.0.2`
- `springdoc` → `3.2.0`
- MyBatis-Plus → `mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser`
- 显式加入 `spring-retry`

编译结果：

```text
程序包 org.springframework.ai.anthropic.api 不存在
程序包 org.springframework.ai.openai.api 不存在
```

Spring AI 2 不再提供 `OpenAiApi` / `AnthropicApi` 这层兼容封装，改用官方 SDK：

- `com.openai.client.OpenAIClient`
- `com.anthropic.client.AnthropicClient`

影响集中在 `ChatModelRouter`：

- 需要重写 Provider 客户端构造；
- 需要验证 DeepSeek/Ollama/自定义 OpenAI-compatible 的 base URL、路径、超时、重试；
- 需要重新验证 Anthropic thinking/tools；
- Spring AI 2 的 `OpenAiChatModel` / `AnthropicChatModel` builder API 也已变化。

这不是小修；生产模型路由是核心链路，必须做完整回归。

### 路径 B：Boot 3.5.16 + Spring Framework 7.0.10 强制覆盖

修改 `spring-framework.version=7.0.10` 后：

- 编译通过；
- 全量测试 **664 个中 2 个错误**：

```text
NoSuchMethodError:
void org.springframework.http.HttpHeaders.addAll(org.springframework.util.MultiValueMap)
```

来源：Spring AI 1.0.7 / 1.1.8 的 `OpenAiApi` 与 Spring 7 二进制度不兼容。

升级 Spring AI 1.1.8 仍复现同样问题，因此**不能通过单独覆盖 Spring Framework 版本解决**。

## 推荐方案

### 短期（现在）

- 保持 Boot 3.5.16 / Spring 6.2.19；
- backend 镜像仍启用 HIGH/CRITICAL 阻断，但用 `.trivyignore` 的逐条 VEX 例外；
- 例外只覆盖当前不可达路径：
  - 不使用 XSLT View；
  - 不使用 WebFlux 函数式端点/View；
  - 不使用 lark-mcp 的 FTP 功能；
- 复查触发条件：Spring Boot 3.5.x 引入 6.2.20+ 修复，或启动 Boot 4 迁移。

### 中期（独立迁移）

建议顺序：

1. 先把 `ChatModelRouter` 抽象成不依赖 Spring AI API 的平台接口；
2. 在独立分支升级 Boot 4 + Spring AI 2，完成 OpenAI/Anthropic 客户端重写；
3. 升级 MyBatis-Plus Boot4 starter、springdoc 3、Spring Retry；
4. 全量 668 测试 + Chrome E2E；
5. 三镜像构建/扫描；
6. 本地 K8s 部署 + 安全兜底/真实模型 smoke；
7. 真实模型/RAG/审核 30 分钟 SLO；
8. 通过后再替换生产。

预计工作量：**至少 1–2 周**，主要成本在模型路由重写和真实链路回归，不是 Maven 版本调整。

## 证据

- Boot 4 编译失败工作树：`/Users/wangpan/.zcode/workspace/default/ai-agent-platform-boot4`
- Spring 7 覆盖测试失败工作树：`/Users/wangpan/.zcode/workspace/default/ai-agent-platform-spring7`
- 当前镜像扫描与 VEX：`docs/镜像供应链.md`、`.trivyignore`
