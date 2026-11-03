#!/usr/bin/env python3
"""MVP 安全/冻结默认值静态门禁（不启动 Docker/MySQL）。

安全默认值的清单在 backend/src/main/resources/security-defaults.json；
本脚本和 Java ProductionGuard 共用该文件，避免两套检查漂移。
"""
from pathlib import Path
import sys

import json

ROOT = Path(__file__).resolve().parent.parent
DEFAULTS_FILE = ROOT / "backend/src/main/resources/security-defaults.json"


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

    # 单一来源的默认值/静态检查。
    try:
        defaults = json.loads(DEFAULTS_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        return fail(f"无法加载 security-defaults.json：{e}")
    for check in defaults.get("staticChecks", []):
        path = check.get("file")
        needle = check.get("contains")
        message = check.get("message") or f"{path} 缺少安全默认值"
        if not path or needle is None:
            return fail(f"security-defaults.json 中的静态检查缺少 file/contains：{check}")
        file_path = ROOT / path
        if not file_path.exists():
            return fail(f"安全默认值检查文件不存在：{path}")
        if needle not in file_path.read_text(encoding="utf-8"):
            return fail(message)

    app = read("backend/src/main/resources/application.yml")
    if "runtime: ${AGENT_DEPLOY_RUNTIME:gateway" in app:
        return fail("application.yml 仍保留旧 Node AGENT_DEPLOY_RUNTIME 配置")
    if (ROOT / "runtime/agent-gateway").exists():
        return fail("runtime/agent-gateway 旧 Node 运行时仍存在")
    if (ROOT / "deploy/k8s/infra/agent-gateway.yaml").exists():
        return fail("旧 agent-gateway K8s manifest 仍存在")
    if not (ROOT / "deploy/helm/ai-agent-platform/templates/agent-runtime-rbac.yaml").exists():
        return fail("缺少 Agent K8s 运行时 RBAC 模板")

    graph = read("backend/src/main/java/com/platform/knowledge/service/KnowledgeGraphService.java")
    if 'configBoolean("knowledge.graph.auto-extract", false)' not in graph:
        return fail("知识图谱自动抽取没有默认关闭")
    if 'configBoolean("knowledge.graph.retrieval-enabled", false)' not in graph:
        return fail("GraphRAG 检索扩展没有默认关闭")

    # 主栈容器加固与 MinIO 控制台默认只绑本机。
    if "x-hardening: &hardening" not in compose:
        return fail("Compose 缺少统一容器加固锚点")
    if "no-new-privileges:true" not in compose:
        return fail("Compose 缺少 no-new-privileges")
    if "cap_drop:" not in compose:
        return fail("Compose 缺少 cap_drop")
    if '"127.0.0.1:${MINIO_CONSOLE_PORT:-9001}:9001"' not in compose:
        return fail("MinIO 控制台未默认绑定 127.0.0.1")
    if ":latest" in compose:
        return fail("docker-compose.yml 仍使用 :latest 浮动标签")

    # 供应链扫描必须覆盖后端、前端和告警中继镜像。
    ci = read(".github/workflows/ci.yml")
    for image in ("backend", "frontend", "alert-relay"):
        if f"- image: {image}" not in ci or "scan-type: image" not in ci:
            return fail(f"CI 未构建/扫描 {image} 镜像")
    release = read(".github/workflows/release.yml")
    for image in ("aap/backend:release", "aap/frontend:release", "alert-relay:release"):
        if image not in release:
            return fail(f"Release 未构建/扫描 {image} 镜像")

    print("MVP security/freeze static checks OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
