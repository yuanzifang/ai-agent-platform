#!/usr/bin/env python3
"""E2E 用 stdio MCP 假 Server：行分隔 JSON-RPC，支持 initialize/tools/list/tools/call。

structuredContent 返回自身 pid，供测试断言会话复用（两次调用同一进程）。
"""
import json
import os
import sys


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    pid = os.getpid()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        if "id" not in msg:
            continue
        rid = msg["id"]
        method = msg.get("method", "")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2025-03-26",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "stdio-mock", "version": "1.0"}}})
            # 主动通知工具列表变化，供平台验证 list_changed 检测
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed", "params": {}})
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": rid, "result": {"tools": [{
                "name": "echo",
                "description": "回显输入",
                "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
                "outputSchema": {"type": "object", "properties": {"echo": {"type": "string"}}},
                "annotations": {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True}}]}})
        elif method == "tools/call":
            params = msg.get("params") or {}
            args = params.get("arguments") or {}
            text = str(args.get("text", ""))
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": "echo: " + text}],
                "structuredContent": {"echo": text, "pid": pid}}})
        else:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "method not found"}})


if __name__ == "__main__":
    main()
