#!/usr/bin/env python3
"""为本地 Agent 配置本机 Ollama 对话模型（默认 qwen2.5:7b-chat）。

流程：
  1. 用 ollama cp 创建对话别名（不改动原 qwen2.5:7b）
  2. 在平台已有 OLLAMA Provider 下登记为 CHAT 模型
  3. 把指定 Agent 的默认模型切到本地聊天模型，并移除云端 fallback
  4. 强制发布 Agent

用法：
  python3 scripts/setup-local-agent-model.py
  python3 scripts/setup-local-agent-model.py --agent-id 4878 --alias qwen2.5:7b-chat
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request


def request_json(method: str, url: str, token: str | None = None,
                 body: dict | None = None, extra_headers: dict | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for key, value in (extra_headers or {}).items():
        req.add_header(key, value)
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


def ollama_names() -> set[str]:
    out = subprocess.check_output(["ollama", "list"], text=True)
    return {line.split()[0] for line in out.splitlines()[1:] if line.strip()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=os.getenv("LOCAL_AGENT_MODEL", "qwen2.5:7b"))
    parser.add_argument("--alias", default=os.getenv("LOCAL_AGENT_CHAT_ALIAS", "qwen2.5:7b-chat"))
    parser.add_argument("--agent-id", type=int, default=int(os.getenv("LOCAL_AGENT_ID", "4878")))
    parser.add_argument("--base-url", default=os.getenv("PLATFORM_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("PLATFORM_USER", "dev"))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", "Dev@123456"))
    parser.add_argument("--context-window", type=int, default=32768)
    args = parser.parse_args()

    if not shutil.which("ollama"):
        print("未找到 ollama，请先安装：https://ollama.com/download", file=sys.stderr)
        return 2
    names = ollama_names()
    if args.model not in names:
        raise RuntimeError(f"Ollama 未安装基础模型 {args.model}")
    if args.alias not in names:
        print(f"创建本地对话别名 {args.model} -> {args.alias}")
        subprocess.check_call(["ollama", "cp", args.model, args.alias])
    else:
        print(f"本地对话别名已存在：{args.alias}")

    base = args.base_url.rstrip("/") + "/api/v1"
    login = request_json("POST", base + "/auth/login", body={
        "username": args.username, "password": args.password
    })
    token = login.get("accessToken")
    if not token:
        raise RuntimeError("登录失败：未返回 accessToken")

    providers = request_json("GET", base + "/provider", token) or []
    provider = next((p for p in providers
                     if (p.get("providerType") or "").upper() == "OLLAMA"
                     and p.get("status") == "ENABLED"), None)
    if not provider:
        raise RuntimeError("未找到启用的 OLLAMA Provider")
    provider_id = provider["id"]

    models = request_json("GET", base + "/provider/models", token) or []
    chat_model = next((m for m in models
                       if m.get("providerId") == provider_id
                       and m.get("modelName") == args.alias), None)
    if chat_model:
        if str(chat_model.get("purpose") or "").upper() != "CHAT":
            raise RuntimeError(f"模型 {args.alias} 已存在但用途不是 CHAT：{chat_model.get('purpose')}")
        print(f"CHAT 模型已登记：id={chat_model.get('modelId')} provider={provider.get('name')}")
    else:
        created = request_json("POST", base + f"/provider/{provider_id}/models", token, {
            "modelName": args.alias,
            "displayName": f"{args.alias} 本地 Agent 对话",
            "purpose": "CHAT",
            "contextWindow": args.context_window,
            "inputPrice": 0,
            "outputPrice": 0,
            "cacheReadPrice": 0,
            "cacheWritePrice": 0,
        })
        print(f"已登记 CHAT 模型：id={created.get('id')} model={args.alias}")

    detail = request_json("GET", base + f"/agent/{args.agent_id}", token)
    if not detail:
        raise RuntimeError(f"Agent {args.agent_id} 不存在")
    config = dict(detail.get("modelConfig") or {})
    config["modelName"] = args.alias
    # 本地演示 Agent 不保留云端 fallback，避免主模型失败时静默走云端。
    config.pop("fallbackModelName", None)
    request_json("PUT", base + f"/agent/{args.agent_id}", token, {"modelConfig": config},
                 {"If-Match": str(detail.get("updatedAt") or "")})
    request_json("POST", base + f"/agent/{args.agent_id}/publish", token, {"force": True})
    print(f"Agent {args.agent_id} 默认模型已切换并发布：{args.alias}")
    print("工作流节点仍可显式使用 qwen2.5:7b；两者都走本机 Ollama。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        raise SystemExit(1)
