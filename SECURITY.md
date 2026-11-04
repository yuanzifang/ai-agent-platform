# 安全策略

## 报告漏洞

**请勿通过公开 Issue、讨论区或 PR 披露安全漏洞。**

请使用 GitHub 私密漏洞报告（仓库 Security 标签页 → Report a vulnerability），
或通过仓库所有者 GitHub 主页（[@yuanzifang](https://github.com/yuanzifang)）私下联系。

- 我们会在 48 小时内确认收到报告
- 修复与公告（如适用）会在协调后发布
- 如你愿意，会在致谢中列出报告者

## 支持范围

仅最新 `main` 分支提供安全修复。

## 内建安全机制（供评审参考）

- CI 门禁：gitleaks 秘密扫描、trivy 依赖与镜像漏洞阻断（HIGH/CRITICAL）、npm audit、CodeQL、SBOM
- 生产熔断：`APP_ENV=production` 时缺失/使用开发默认密钥（JWT/AES/数据库/MinIO/DEV_PASSWORD 等）将拒绝启动（`ProductionGuard`）
- Agent 工具网络白名单（`TOOL_ALLOW_PRIVATE`）、Prompt 注入启发式检测等，详见 `docs/` 目录
