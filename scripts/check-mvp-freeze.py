#!/usr/bin/env python3
"""MVP 安全/冻结默认值静态门禁（不启动 Docker/MySQL）。"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def fail(message: str):
    print(f"FAIL: {message}", file=sys.stderr)
    return 1


def main() -> int:
    compose = read("docker-compose.yml")
    # 允许注释里提到 docker.sock；只禁止真实挂载
    active_lines = [line for line in compose.splitlines() if not line.lstrip().startswith("#")]
    if any("/var/run/docker.sock" in line for line in active_lines):
        return fail("docker-compose.yml 仍挂载 /var/run/docker.sock")
    promtail = read("deploy/observability/promtail.yml")
    if "docker_sd_configs" in promtail:
        return fail("promtail 仍使用 docker_sd_configs")
    if 'profiles: ["obs"]' not in compose:
        return fail("可观测服务未设置 obs profile")

    app = read("backend/src/main/resources/application.yml")
    if "enabled: ${AGENT_DEPLOY_ENABLED:true}" not in app:
        return fail("Agent 部署默认开关异常")
    if "bind: ${AGENT_DEPLOY_BIND:127.0.0.1}" not in app:
        return fail("Agent 部署默认绑定不是 127.0.0.1")
    if "cors-origins: ${AGENT_DEPLOY_CORS_ORIGINS:}" not in app:
        return fail("Agent 部署 CORS 默认没有收敛")

    graph = read("backend/src/main/java/com/platform/knowledge/service/KnowledgeGraphService.java")
    if 'configBoolean("knowledge.graph.auto-extract", false)' not in graph:
        return fail("知识图谱自动抽取没有默认关闭")
    if 'configBoolean("knowledge.graph.retrieval-enabled", false)' not in graph:
        return fail("GraphRAG 检索扩展没有默认关闭")

    print("MVP security/freeze static checks OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
