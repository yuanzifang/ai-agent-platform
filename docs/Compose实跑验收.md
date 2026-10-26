# Docker Compose 全栈首次实跑验收

日期：2026-10-26  
环境：macOS Apple Silicon + Colima + Docker 29.5.2 + Docker Compose 5.6.1  
代码基线：`292262e` + 本次 Dockerfile/Compose 修复

## 结论

核心全栈已通过 `docker compose up -d --build` 实际启动并完成业务链路验证：

| 服务 | 状态 | 验证 |
|---|---|---|
| MySQL 8.4 | healthy | Flyway 迁移、登录/知识库数据 |
| Redis 7.4 | healthy | 后端连接、缓存/限流 |
| PgVector 16 | healthy | `vector` 扩展、`tenant_1.embeddings_1024` 写入 1 行 |
| Ollama | healthy | 拉取 `bge-m3` 后真实 embedding |
| Backend | healthy | `/actuator/health`、登录、知识库 API |
| Frontend | healthy | 静态页、`/api` 反向代理、CORS/安全头 |
| Prometheus | running | `ai-agent-backend` target `up` |
| Grafana | running | `/api/health` database ok |
| MinIO | 外部 | 宿主机 MinIO（Docker Hub 官方镜像 403） |

## 实跑命令

```bash
cp .env.docker.example .env.docker
# 填 MYSQL/Redis/PgVector/MinIO/JWT/AES/管理员密码
# 国内构建可加：MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public

# 官方 MinIO 镜像可拉取时：
docker compose --env-file .env.docker up -d --build

# 官方 MinIO 镜像不可拉取时（本机已启动 MinIO）：
docker compose --env-file .env.docker \
  -f docker-compose.yml -f docker-compose.external-minio.yml up -d --build

docker compose --env-file .env.docker exec ollama ollama pull bge-m3
```

本次实际使用 18089 前端端口、19090 Prometheus、13000 Grafana、19002 MinIO console 占位；生产按 `.env.docker` 的默认端口即可。

## 业务链路验证

1. 前端 `/healthz` 200，`/api/actuator/health` 200。
2. `dev / Dev@123456 / tenantKey=dev` 登录成功，`/api/v1/agent` 返回空列表。
3. 创建知识库 → 预签名上传 `smoke.txt` 到 MinIO → callback → 解析 `DONE`、`chunkCount=1`。
4. PgVector `tenant_1.embeddings_1024` 计数为 1。
5. Prometheus `ai-agent-backend` target `up`；Grafana `/api/health` 正常。

## 本次发现并修复

1. **backend 镜像启动失败**：Dockerfile 未把 `lombok.config` 复制进构建上下文，Lombok 未把 `@Qualifier` 复制到构造器参数，Spring 在 4 个 `TaskExecutor` 中无法选择。已补 `COPY lombok.config .`。
2. **Apple Silicon 构建失败**：`eclipse-temurin:17-jre-alpine` 无 arm64 manifest。运行阶段改为 `node:22-bookworm-slim` + `openjdk-17-jre-headless`，仍保留 Python/Node/curl。
3. **MinIO 官方镜像不可拉取**：Docker Hub/Quay 返回 403/401。新增 `docker-compose.external-minio.yml`，支持连接宿主机 MinIO；其余服务仍由 Compose 启动。
4. **构建慢**：新增可选 `MAVEN_MIRROR_URL` 构建参数，国内可指向阿里云 Maven 镜像。
5. **PgVector 扩展**：首次 volume 挂载路径异常导致 init 脚本未执行；在共享目录下重建 volume 后 `vector` 扩展正常。生产需确认 `/docker-entrypoint-initdb.d` 挂载生效。

## 仍缺

- Helm `install` 尚未实跑。
- Compose 目前依赖宿主机 MinIO（或自建镜像仓库）；完全自包含的 MinIO 镜像分发未解决。
- Ollama 模型不会自动拉取，首次部署需手动 `ollama pull bge-m3`。
- 未做 100+ 并发/30 分钟长稳压测；本次只验证功能链路。
