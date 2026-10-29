#!/bin/bash
# 一键构建并启动 Docker Compose 生产/测试环境
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "✘ 未检测到 Docker。请先安装 Docker Desktop 或 Docker Engine："
  echo "  https://docs.docker.com/engine/install/"
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "✘ 未检测到 Docker Compose v2（docker compose）。"
  exit 1
fi

ENV_FILE="$ROOT/.env.docker"
if [ ! -f "$ENV_FILE" ]; then
  echo "→ 生成 .env.docker（强随机密码）"
  python3 - <<'PY'
import pathlib, secrets
src = pathlib.Path('.env.docker.example').read_text()
replacements = {}
for key in ('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'MYSQL_EXPORTER_PASSWORD',
            'REDIS_PASSWORD', 'REDIS_EXPORTER_PASSWORD', 'PG_PASSWORD', 'PG_EXPORTER_PASSWORD', 'MINIO_ROOT_PASSWORD'):
    replacements[key] = secrets.token_hex(24)
replacements['JWT_SECRET'] = secrets.token_hex(32)
replacements['AES_KEY'] = secrets.token_hex(32)
# 满足常见密码策略：大小写 + 数字 + 特殊字符
replacements['PLATFORM_ADMIN_PASSWORD'] = 'Aa1!' + secrets.token_hex(12)
replacements['GRAFANA_ADMIN_PASSWORD'] = 'Aa1!' + secrets.token_hex(12)
lines = []
for line in src.splitlines():
    key = line.split('=', 1)[0] if '=' in line else ''
    if key in replacements:
        line = f'{key}={replacements[key]}'
    lines.append(line)
pathlib.Path('.env.docker').write_text('\n'.join(lines) + '\n')
PY
  echo "  已生成：$ENV_FILE"
  echo "  请填写 DEEPSEEK_API_KEY；不填也能启动，但对话/摘要等模型功能不可用。"
fi

# 读取 HTTP_PORT 用于就绪检查
HTTP_PORT="$(grep -E '^HTTP_PORT=' "$ENV_FILE" | tail -1 | cut -d= -f2)"
HTTP_PORT="${HTTP_PORT:-80}"

echo "→ 构建并启动服务（首次构建需要几分钟）"
docker compose --env-file "$ENV_FILE" up -d --build

echo "→ 等待前端就绪 http://localhost:${HTTP_PORT}/healthz"
for i in $(seq 1 60); do
  if curl -fsS "http://localhost:${HTTP_PORT}/healthz" >/dev/null 2>&1; then
    break
  fi
  sleep 5
done

# 拉取本地嵌入模型（知识库需要）；默认优先 bge-m3，后端会在未安装时回退 nomic
echo "→ 拉取 Ollama 嵌入模型 bge-m3（中文推荐）"
docker compose --env-file "$ENV_FILE" exec -T ollama ollama pull bge-m3 \
  || echo "  ⚠ bge-m3 拉取失败，请稍后手动执行：docker compose --env-file .env.docker exec ollama ollama pull bge-m3"
echo "→ 拉取 Ollama 兼容模型 nomic-embed-text"
docker compose --env-file "$ENV_FILE" exec -T ollama ollama pull nomic-embed-text \
  || echo "  ⚠ nomic 拉取失败，请稍后手动执行：docker compose --env-file .env.docker exec ollama ollama pull nomic-embed-text"

ADMIN_PASSWORD="$(grep -E '^PLATFORM_ADMIN_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
GRAFANA_PASSWORD="$(grep -E '^GRAFANA_ADMIN_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
echo
echo "✔ 前端：      http://localhost:${HTTP_PORT}"
echo "✔ 后端健康：  http://localhost:${HTTP_PORT}/api/actuator/health（经前端代理，默认不直接暴露 8080）"
echo "✔ MinIO 控制台：http://localhost:$(grep -E '^MINIO_CONSOLE_PORT=' "$ENV_FILE" | tail -1 | cut -d= -f2 || echo 9001)"
echo "✔ Prometheus： http://localhost:$(grep -E '^PROMETHEUS_PORT=' "$ENV_FILE" | tail -1 | cut -d= -f2 || echo 9090)"
echo "✔ Grafana：    http://localhost:$(grep -E '^GRAFANA_PORT=' "$ENV_FILE" | tail -1 | cut -d= -f2 || echo 3000)  admin / ${GRAFANA_PASSWORD}"
echo "✔ 平台管理员：admin / ${ADMIN_PASSWORD}"
echo
echo "查看状态：docker compose --env-file .env.docker ps"
echo "查看日志：docker compose --env-file .env.docker logs -f backend"
echo "停止：    scripts/docker-down.sh"
