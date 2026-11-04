# 贡献指南

欢迎 Issue 与 PR。改动无论大小，请尽量附带说明与测试。

## 开发环境

参照 [README.md](README.md) 完成本机准备（后端 Spring Boot 3 / 前端 Vue3 + Vite；本机直跑无需 Docker）。

## 提交前自查

```bash
# 后端单元测试
cd backend && mvn test

# 前端单测与构建
cd frontend && npm run test:unit && npm run build
```

- 涉及数据库结构：新增 Flyway 迁移（`backend/src/main/resources/db/migration/`，版本号递增），不修改已合入的历史迁移
- 涉及配置项：同步 `application.yml` 注释、`.env.docker.example` 与 Helm `values.yaml`
- 涉及 CI/部署：`bash -n scripts/*.sh` 与 `docker compose config -q` 本地校验通过

## 提交与 PR 规范

- 提交信息使用 Conventional Commits（`feat:` / `fix:` / `docs:` / `ci:` / `refactor:` 等，中文描述即可）
- 一个 PR 聚焦一件事；修复类 PR 请在描述中写清现象、根因与验证方式
- CI（GitHub Actions）为常驻门禁：秘密扫描、依赖与镜像漏洞阻断、构建、单测、E2E 需全部通过

## 行为准则

参与本项目即表示同意遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 许可

提交即表示同意你的贡献以 [Apache License 2.0](LICENSE) 许可发布。
