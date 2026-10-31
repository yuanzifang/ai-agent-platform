#!/usr/bin/env python3
"""清理 E2E 残留数据（默认 dry-run）。

默认只列出，不删除：
  Agent: E2E... / LOCAL-SMOKE-... / LOCAL-REWRITE-...
  Provider: E2E ...
  临时模型: broken-model-* / claude-mock

确认后：
  --apply                 删除 E2E Agent（先删工作流，再删 Agent）
  --apply --apply-providers
                          同时用 mysql 清理 E2E Provider/临时模型
                          mysql 连接参数可用 MYSQL_* 环境变量覆盖；
                          密码只从 MYSQL_PASSWORD 读取，不写入命令行参数。

用法：
  python3 scripts/cleanup-e2e-data.py
  python3 scripts/cleanup-e2e-data.py --apply
  MYSQL_PASSWORD=*** python3 scripts/cleanup-e2e-data.py --apply --apply-providers
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

PREFIXES = ("E2E", "LOCAL-SMOKE-", "LOCAL-REWRITE-")


def mysql_exec(sql: str, host: str, port: str, user: str, database: str) -> str:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("未找到 mysql 客户端；Provider/模型清理需要本机 mysql 命令")
    env = os.environ.copy()
    password = os.getenv("MYSQL_PASSWORD", "")
    if password:
        env["MYSQL_PWD"] = password
    proc = subprocess.run(
        [mysql, "-h", host, "-P", str(port), "-u", user, "-N", "-B", database],
        input=sql, text=True, capture_output=True, env=env
    )
    if proc.returncode != 0:
        raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:500]}")
    return proc.stdout.strip()


def call(method: str, url: str, token: str | None = None, body: dict | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise RuntimeError(f"{method} {url} -> HTTP {e.code}: {detail}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"{method} {url} 失败：{e.reason}") from e
    if payload.get("code") not in (None, 0):
        raise RuntimeError(f"{method} {url} -> {payload.get('message')}")
    return payload.get("data")


def is_e2e_name(name: str) -> bool:
    return bool(name) and name.startswith(PREFIXES)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("PLATFORM_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("PLATFORM_USER", "dev"))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", "Dev@123456"))
    parser.add_argument("--apply", action="store_true",
                        help="实际删除 E2E Agent；默认只 dry-run")
    parser.add_argument("--apply-providers", action="store_true",
                        help="同时用 mysql 清理 E2E Provider/临时模型；必须与 --apply 同用")
    parser.add_argument("--mysql-host", default=os.getenv("MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", default=os.getenv("MYSQL_PORT", "3306"))
    parser.add_argument("--mysql-user", default=os.getenv("MYSQL_USERNAME", "root"))
    parser.add_argument("--mysql-database", default=os.getenv("MYSQL_DATABASE", "ai_agent_platform"))
    args = parser.parse_args()
    if args.apply_providers and not args.apply:
        parser.error("--apply-providers 必须与 --apply 一起使用")
    base = args.base_url.rstrip("/") + "/api/v1"

    login = call("POST", base + "/auth/login", body={
        "username": args.username, "password": args.password
    })
    token = login.get("accessToken")
    if not token:
        raise RuntimeError("登录失败：未返回 accessToken")

    agents = []
    page = 1
    while True:
        data = call("GET", base + f"/agent?page={page}&pageSize=100", token) or {}
        rows = data.get("list") or []
        agents.extend(rows)
        total = int(data.get("total") or 0)
        if not rows or len(agents) >= total or page >= 100:
            break
        page += 1

    candidates = [a for a in agents if is_e2e_name(str(a.get("name") or ""))]
    print(f"Agent 总数: {len(agents)}")
    print(f"命中 E2E Agent: {len(candidates)}")
    for a in candidates:
        print(f"  - id={a.get('id')} status={a.get('status')} name={a.get('name')}")

    providers = call("GET", base + "/provider", token) or []
    provider_hits = [p for p in providers if is_e2e_name(str(p.get("name") or ""))]
    print(f"\nProvider 总数: {len(providers)}；命中 E2E Provider: {len(provider_hits)}")
    for p in provider_hits[:80]:
        print(f"  - id={p.get('id')} type={p.get('providerType')} name={p.get('name')}")
    if len(provider_hits) > 80:
        print(f"  ... 另有 {len(provider_hits) - 80} 个")

    models = call("GET", base + "/provider/models", token) or []
    model_hits = [m for m in models
                  if is_e2e_name(str(m.get("modelName") or ""))
                  or str(m.get("modelName") or "").startswith(("broken-model-", "claude-mock"))]
    print(f"\n模型总数: {len(models)}；命中 E2E/临时模型: {len(model_hits)}")
    for m in model_hits[:80]:
        print(f"  - id={m.get('modelId')} providerId={m.get('providerId')} "
              f"name={m.get('modelName')} purpose={m.get('purpose')} status={m.get('status')}")

    if not args.apply:
        print("\nDRY-RUN：未删除任何数据。确认后加 --apply 删除上面的 E2E Agent。")
        print("Provider/模型当前只报告，不做破坏性删除。")
        return 0

    deleted = 0
    for a in candidates:
        agent_id = a.get("id")
        try:
            call("DELETE", base + f"/agent/{agent_id}/workflow", token)
        except Exception:
            pass
        try:
            call("DELETE", base + f"/agent/{agent_id}", token)
            deleted += 1
        except Exception as e:
            print(f"  删除失败 id={agent_id}: {e}", file=sys.stderr)
    print(f"\n已删除 E2E Agent: {deleted}/{len(candidates)}")

    if not args.apply_providers:
        print("Provider/模型仍保留；确认后追加 --apply-providers 清理测试 Provider/模型。")
        return 0

    # 先确认没有未删除 Agent 引用测试模型，避免误删仍在使用的测试数据。
    ref_sql = """
SELECT COUNT(*) FROM agent_registry
WHERE status <> 'DELETED' AND (
  JSON_UNQUOTE(JSON_EXTRACT(model_config, '$.modelName')) LIKE 'broken-model-%'
  OR JSON_UNQUOTE(JSON_EXTRACT(model_config, '$.fallbackModelName')) LIKE 'broken-model-%'
  OR JSON_UNQUOTE(JSON_EXTRACT(model_config, '$.modelName')) = 'claude-mock'
  OR JSON_UNQUOTE(JSON_EXTRACT(model_config, '$.fallbackModelName')) = 'claude-mock'
);
""".strip()
    refs = int(mysql_exec(ref_sql, args.mysql_host, args.mysql_port,
                           args.mysql_user, args.mysql_database) or "0")
    if refs > 0:
        raise RuntimeError(f"仍有 {refs} 个未删除 Agent 引用临时模型，已中止 Provider 清理")

    cleanup_sql = """
START TRANSACTION;
DELETE FROM model
WHERE model_name LIKE 'broken-model-%' OR model_name = 'claude-mock';
DELETE FROM model
WHERE provider_id IN (SELECT id FROM provider WHERE name LIKE 'E2E %');
DELETE FROM provider_credential
WHERE provider_id IN (SELECT id FROM provider WHERE name LIKE 'E2E %');
DELETE FROM provider
WHERE name LIKE 'E2E %';
COMMIT;
""".strip()
    mysql_exec(cleanup_sql, args.mysql_host, args.mysql_port,
               args.mysql_user, args.mysql_database)
    remaining_providers = mysql_exec(
        "SELECT COUNT(*) FROM provider WHERE name LIKE 'E2E %';",
        args.mysql_host, args.mysql_port, args.mysql_user, args.mysql_database)
    remaining_models = mysql_exec(
        "SELECT COUNT(*) FROM model WHERE model_name LIKE 'broken-model-%' OR model_name = 'claude-mock';",
        args.mysql_host, args.mysql_port, args.mysql_user, args.mysql_database)
    print(f"已清理 E2E Provider，剩余={remaining_providers}；临时模型剩余={remaining_models}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        raise SystemExit(1)
