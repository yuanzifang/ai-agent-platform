#!/usr/bin/env python3
"""安装并登记平台专用本地改写模型（默认 qwen2.5:7b）。

用法：
  python3 scripts/setup-local-eval-model.py
  LOCAL_EVAL_MODEL=qwen3.6:27b python3 scripts/setup-local-eval-model.py

要求：本机已安装 Ollama；平台后端已启动；当前用户有 model:manage 权限。
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request


def request_json(method: str, url: str, token: str | None, body: dict | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise RuntimeError(f"{method} {url} -> HTTP {e.code}: {detail}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"{method} {url} 失败：{e.reason}") from e
    if payload.get("code") not in (None, 0):
        raise RuntimeError(f"{method} {url} -> {payload.get('message')}")
    return payload.get("data")


def ollama_models() -> set[str]:
    out = subprocess.check_output(["ollama", "list"], text=True)
    return {line.split()[0] for line in out.splitlines()[1:] if line.strip()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=os.getenv("LOCAL_EVAL_MODEL", "qwen2.5:7b"))
    parser.add_argument("--base-url", default=os.getenv("PLATFORM_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("PLATFORM_USER", "dev"))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", "Dev@123456"))
    parser.add_argument("--provider-name", default=os.getenv("OLLAMA_PROVIDER_NAME", ""))
    parser.add_argument("--context-window", type=int, default=32768)
    args = parser.parse_args()

    if not shutil.which("ollama"):
        print("未找到 ollama，请先安装：https://ollama.com/download", file=sys.stderr)
        return 2
    if args.model not in ollama_models():
        print(f"拉取本地模型 {args.model} ...")
        subprocess.check_call(["ollama", "pull", args.model])

    base = args.base_url.rstrip("/") + "/api/v1"
    login = request_json("POST", base + "/auth/login", None,
                         {"username": args.username, "password": args.password})
    token = login.get("accessToken")
    if not token:
        raise RuntimeError("登录失败：未返回 accessToken")

    providers = request_json("GET", base + "/provider", token) or []
    candidates = [
        p for p in providers
        if (p.get("providerType") or "").upper() == "OLLAMA"
        or "ollama" in (p.get("name") or "").lower()
    ]
    if args.provider_name:
        candidates = [p for p in candidates if p.get("name") == args.provider_name]
    if not candidates:
        raise RuntimeError("未找到 Ollama Provider，请先创建 providerType=OLLAMA 的 Provider")
    provider = candidates[0]
    provider_id = provider["id"]

    models = request_json("GET", base + "/provider/models", token) or []
    existing = next((m for m in models
                     if m.get("providerId") == provider_id and m.get("modelName") == args.model), None)
    if existing:
        print(f"模型已登记：id={existing.get('modelId')} purpose={existing.get('purpose')} "
              f"provider={provider.get('name')}")
        if existing.get("purpose") != "PLATFORM":
            print("警告：该模型不是 PLATFORM 用途，Agent 仍可能选择它；请在模型接入中改为平台专用。")
    else:
        created = request_json("POST", base + f"/provider/{provider_id}/models", token, {
            "modelName": args.model,
            "displayName": f"{args.model} 平台评测专用",
            "purpose": "PLATFORM",
            "contextWindow": args.context_window,
        })
        print(f"已登记平台模型：id={created.get('id')} model={args.model} provider={provider.get('name')}")

    print("\n验证：")
    print(f"  E2E_LOCAL_REWRITE=1 E2E_LOCAL_REWRITE_MODEL={args.model} "
          f"./node_modules/.bin/playwright test e2e/eval-step.spec.ts:100 --reporter=list")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        raise SystemExit(1)
