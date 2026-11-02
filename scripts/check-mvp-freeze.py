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
    if "enabled: ${SKILL_SCRIPT_ENABLED:false}" not in app:
        return fail("技能脚本默认未关闭（SKILL_SCRIPT_ENABLED 必须默认 false）")
    if "runtime: ${SKILL_SCRIPT_RUNTIME:process}" not in app:
        return fail("技能脚本基础 runtime 配置异常")
    prod = read("backend/src/main/resources/application-prod.yml")
    if "runtime: ${SKILL_SCRIPT_RUNTIME:docker}" not in prod:
        return fail("生产 profile 未强制技能脚本 runtime=docker")
    runner = read("backend/src/main/java/com/platform/capability/runtime/ScriptRunnerService.java")
    if '@Value("${skill.script.enabled:false}")' not in runner:
        return fail("ScriptRunnerService 的技能脚本默认开关未关闭")
    if "已拒绝执行" not in runner:
        return fail("Docker 沙箱不可用时未 fail-closed")
    if "bind: ${AGENT_DEPLOY_BIND:127.0.0.1}" not in app:
        return fail("Agent 部署默认绑定不是 127.0.0.1")
    if "cors-origins: ${AGENT_DEPLOY_CORS_ORIGINS:}" not in app:
        return fail("Agent 部署 CORS 默认没有收敛")

    # 单栈守卫：旧 Node 运行时和旧 K8s manifest 不得回退
    if "runtime: ${AGENT_DEPLOY_RUNTIME:gateway" in app:
        return fail("application.yml 仍保留旧 Node AGENT_DEPLOY_RUNTIME 配置")
    if (ROOT / "runtime/agent-gateway").exists():
        return fail("runtime/agent-gateway 旧 Node 运行时仍存在")
    if (ROOT / "deploy/k8s/infra/agent-gateway.yaml").exists():
        return fail("旧 agent-gateway K8s manifest 仍存在")
    values = read("deploy/helm/ai-agent-platform/values.yaml")
    compose = read("docker-compose.yml")
    if "SKILL_SCRIPT_ENABLED: ${SKILL_SCRIPT_ENABLED:-false}" not in compose:
        return fail("Compose 技能脚本默认开关未关闭")
    if "SKILL_SCRIPT_RUNTIME: ${SKILL_SCRIPT_RUNTIME:-docker}" not in compose:
        return fail("Compose 技能脚本默认运行时不是 docker")
    if 'skillScriptEnabled: "false"' not in values:
        return fail("Helm 技能脚本默认开关未关闭")
    if 'skillScriptRuntime: "docker"' not in values:
        return fail("Helm 技能脚本默认运行时不是 docker")
    if "automountServiceAccountToken: false" not in values:
        return fail("Helm ServiceAccount 默认仍自动挂载 K8s API Token")
    backend = read("deploy/helm/ai-agent-platform/templates/backend-deployment.yaml")
    if "automountServiceAccountToken: {{ .Values.agentDeploy.enabled }}" not in backend:
        return fail("backend 未按 agentDeploy 开关控制 SA token 挂载")
    frontend = read("deploy/helm/ai-agent-platform/templates/frontend-deployment.yaml")
    if "automountServiceAccountToken: false" not in frontend:
        return fail("frontend 仍自动挂载 SA token")
    if not (ROOT / "deploy/helm/ai-agent-platform/templates/agent-runtime-rbac.yaml").exists():
        return fail("缺少 Agent K8s 运行时 RBAC 模板")

    graph = read("backend/src/main/java/com/platform/knowledge/service/KnowledgeGraphService.java")
    if 'configBoolean("knowledge.graph.auto-extract", false)' not in graph:
        return fail("知识图谱自动抽取没有默认关闭")
    if 'configBoolean("knowledge.graph.retrieval-enabled", false)' not in graph:
        return fail("GraphRAG 检索扩展没有默认关闭")

    print("MVP security/freeze static checks OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
