#!/bin/bash
# 一键启动开发环境（本机模式，无需 Docker）
set -e
export PATH="$HOME/tools/node-v22.14.0-darwin-arm64/bin:$PATH"
MVN="$HOME/tools/apache-maven-3.9.16/bin/mvn"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 1. 基础设施（brew services 开机自启，这里兜底检查）
nc -z localhost 3306 >/dev/null 2>&1 || brew services start mysql
nc -z localhost 6379 >/dev/null 2>&1 || brew services start redis
for i in $(seq 1 15); do nc -z localhost 3306 >/dev/null 2>&1 && break; sleep 2; done

# 2. 后端（缺库时自动创建；密码默认 root，可经 MYSQL_PASSWORD 覆盖）
mysql -uroot -p${MYSQL_PASSWORD:-root} -e "CREATE DATABASE IF NOT EXISTS ai_agent_platform DEFAULT CHARACTER SET utf8mb4;" 2>/dev/null || true

# 3. 杀掉旧实例，起后端
lsof -nP -tiTCP:8080 -sTCP:LISTEN | xargs kill 2>/dev/null || true
sleep 1
cd "$ROOT/backend"
# 加载 .env（DEEPSEEK_API_KEY 等敏感配置，gitignore，勿提交）
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
PLATFORM_ADMIN_PASSWORD="${PLATFORM_ADMIN_PASSWORD:-Admin@123456}" nohup "$MVN" -q spring-boot:run > /tmp/backend.log 2>&1 &
echo "backend starting -> /tmp/backend.log"

# 4. 前端
lsof -nP -tiTCP:5173 -sTCP:LISTEN | xargs kill 2>/dev/null || true
cd "$ROOT/frontend"
nohup npm run dev > /tmp/frontend.log 2>&1 &
echo "frontend starting -> /tmp/frontend.log"

# 5. 等待就绪
for i in $(seq 1 30); do curl -s -o /dev/null http://localhost:8080/actuator/health && break; sleep 3; done
for i in $(seq 1 20); do curl -s -o /dev/null http://localhost:5173 && break; sleep 2; done
echo
echo "✔ 后端  http://localhost:8080  (health: $(curl -s http://localhost:8080/actuator/health | head -c 60))"
echo "✔ 前端  http://localhost:5173   (admin / ${PLATFORM_ADMIN_PASSWORD:-Admin@123456})"
