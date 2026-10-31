#!/usr/bin/env python3
"""清理 E2E 残留数据（默认 dry-run）。

只清理名称明确属于 E2E/本地 smoke 的 Agent：
  E2E...
  LOCAL-SMOKE-...
  LOCAL-REWRITE-...

默认只列出，不删除。确认后加 --apply 才删除 Agent（先删工作流，再删 Agent）。
Provider / 模型没有暴露删除接口，当前只报告残留，不做破坏性操作。

用法：
  python3 scripts/cleanup-e2e-data.py
  python3 scripts/cleanup-e2e-data.py --apply
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

PREFIXES = ("E2E", "LOCAL-SMOKE-", "LOCAL-REWRITE-")


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
    args = parser.parse_args()
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
    print("Provider/模型仍保留，请通过模型接入页面或数据库脚本单独清理。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        raise SystemExit(1)
