## 变更说明

（做了什么、为什么；修复类 PR 写清现象 → 根因 → 验证）

## 自查清单

- [ ] 后端测试通过：`cd backend && mvn test`
- [ ] 前端校验通过：`cd frontend && npm run test:unit && npm run build`
- [ ] 涉及数据库结构：已新增 Flyway 迁移（不改历史迁移）
- [ ] 涉及配置项：已同步 `application.yml` / `.env.docker.example` / Helm `values.yaml`
- [ ] 已更新相关文档（`docs/`）
