#!/usr/bin/env python3
"""为 CI/本地自动化准备 Ollama Provider 与 qwen2.5:7b 模型记录。

只登记模型，不修改 Agent；工作流节点会显式使用 qwen2.5:7b。

用法：
  python3 scripts/setup-local-ci-model.py
  python3 scripts/setup-local-ci-model.py --model qwen2.5:7b
"""
import argparse
import json
import os
import urllib.error
import urllib.request


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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("PLATFORM_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("PLATFORM_USER", "dev"))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", "Dev@123456"))
    parser.add_argument("--model", default=os.getenv("LOCAL_CI_MODEL", "qwen2.5:7b"))
    parser.add_argument("--context-window", type=int, default=32768)
    args = parser.parse_args()
    base = args.base_url.rstrip("/") + "/api/v1"

    login = call("POST", base + "/auth/login", body={
        "username": args.username, "password": args.password
    })
    token = login.get("accessToken")
    if not token:
        raise RuntimeError("登录失败：未返回 accessToken")

    providers = call("GET", base + "/provider", token) or []
    provider = next((p for p in providers
                     if (p.get("providerType") or "").upper() == "OLLAMA"
                     and p.get("status") == "ENABLED"), None)
    if provider:
        print(f"Ollama Provider 已存在：id={provider['id']} name={provider['name']}")
    else:
        created = call("POST", base + "/provider", token, {
            "name": "CI Ollama",
            "providerType": "OLLAMA",
            "baseUrl": os.getenv("OLLAMA_ENDPOINT", "http://127.0.0.1:11434"),
            "timeoutMs": 120000,
            "retryTimes": 1,
        })
        provider = {"id": created["id"], "name": "CI Ollama"}
        print(f"已创建 Ollama Provider：id={provider['id']}")

    models = call("GET", base + "/provider/models", token) or []
    model = next((m for m in models
                  if m.get("providerId") == provider["id"]
                  and m.get("modelName") == args.model), None)
    if model:
        print(f"本地模型已存在：id={model.get('modelId')} model={args.model} purpose={model.get('purpose')}")
    else:
        created = call("POST", base + f"/provider/{provider['id']}/models", token, {
            "modelName": args.model,
            "displayName": f"{args.model} 本地工作流 E2E",
            "purpose": "PLATFORM",
            "contextWindow": args.context_window,
            "inputPrice": 0,
            "outputPrice": 0,
            "cacheReadPrice": 0,
            "cacheWritePrice": 0,
        })
        print(f"已登记本地模型：id={created.get('id')} model={args.model}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=__import__("sys").stderr)
        raise SystemExit(1)
