#!/usr/bin/env python3
"""离线 Mock：OpenAI 兼容对话(18080) + Ollama 嵌入(11434) + 渠道出站捕获(18092) + MCP HTTP(18093)。

供 E2E 在没有真实模型/嵌入/渠道/MCP 时跑通核心链路；仅用于测试环境。
MCP Mock 强制 Mcp-Session-Id 会话（未携带返回 404），用于验证会话复用。
"""
import base64
import hashlib
import json
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CAPTURED = []
LAST_OPENAI = {}
LAST_CHAT = {}
CHAT_COUNT = {"value": 0}


def answer_for(body):
    messages = body.get("messages") or []
    last_user = ""
    system = ""
    for m in messages:
        role = m.get("role")
        content = m.get("content")
        if isinstance(content, list):
            content = " ".join(str(p.get("text", "")) for p in content if isinstance(p, dict))
        content = str(content or "")
        if role == "system":
            system += " " + content
        if role == "user":
            last_user = content
        if role == "tool":
            if "36" in content:
                return "计算完成：36。"
            return f"工具调用完成：{content[:100]}"
    tools = body.get("tools") or []
    if tools and ("计算" in last_user or "calc" in json.dumps(tools).lower()):
        name = tools[0].get("function", {}).get("name", "calculator")
        return {"tool_call": name}
    if "模型回归裁判" in system:
        return json.dumps({"score": 0.9, "reason": "语义一致"}, ensure_ascii=False)
    if "质量审查" in system:
        return json.dumps({"score": 0.2, "issues": "信息不完整", "revised": "这是纠正后的答案。"},
                          ensure_ascii=False)
    if "记忆抽取" in system:
        if "请记住" in last_user:
            return json.dumps({"memories": [{"content": "用户是测试工程师", "category": "PROFILE"}]},
                              ensure_ascii=False)
        return json.dumps({"memories": []}, ensure_ascii=False)
    if "流式审核" in last_user:
        return "这是正常的流式内容。" * 10 + "赌博内容"
    if "触发审核" in last_user:
        return "这是模拟回答：赌博内容示例。"
    if last_user.startswith("基于交接结果复核"):
        return "回传复核完成：已接收目标 Agent 结果。"
    if "只回答OK" in system or "只回答OK" in last_user:
        return "OK"
    if "markdown测试" in last_user:
        return "**加粗文本**\n\n| 列A | 列B |\n| --- | --- |\n| 1 | 2 |\n\n```js\nconst answer = 42\nconsole.log(answer)\n```"
    if "裁判" in system or "评分" in system:
        return '{"score":0.8,"reason":"mock 裁判"}'
    if "提示词工程师" in system or "提示词优化" in system:
        return "优化后的提示词：你是专业助手。请先给出结论，再补充必要细节；不确定时明确说明，不要编造。"
    if "压缩" in system:
        return "对话要点：用户咨询产品问题，上下文已压缩。"
    return "这是模拟回答：已收到你的问题。"


def chat_payload(body, content):
    return {
        "id": "chatcmpl-mock",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": body.get("model", "mock-model"),
        "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                     "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 20, "completion_tokens": 8, "total_tokens": 28},
    }


def tool_call_payload(body, name):
    return {
        "id": "chatcmpl-mock-tool",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": body.get("model", "mock-model"),
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_mock_1",
                    "type": "function",
                    "function": {"name": name, "arguments": "{\"expression\":\"12*3\"}"}
                }]
            },
            "finish_reason": "tool_calls"
        }],
        "usage": {"prompt_tokens": 20, "completion_tokens": 8, "total_tokens": 28},
    }



def read_body(handler):
    """兼容 Content-Length 与 chunked（Spring RestClient/JDK HttpClient 可能用 chunked）。"""
    if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
        data = b""
        while True:
            line = handler.rfile.readline().strip()
            size = int(line.split(b";")[0], 16)
            if size == 0:
                handler.rfile.readline()
                break
            data += handler.rfile.read(size)
            handler.rfile.readline()
        return data
    n = int(handler.headers.get("Content-Length", 0))
    return handler.rfile.read(n) if n else b""


class OpenAiHandler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/last":
            return self._json(200, LAST_OPENAI)
        if self.path == "/last-chat":
            return self._json(200, LAST_CHAT)
        if self.path == "/count":
            return self._json(200, {"count": CHAT_COUNT["value"]})
        if self.path.endswith("/models"):
            return self._json(200, {"data": [{"id": "mock-model", "name": "Mock Model"}]})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.endswith("/chat/completions"):
            return self._json(404, {"error": "not found"})
        body = json.loads(read_body(self) or b"{}")
        CHAT_COUNT["value"] += 1
        print(f"[openai] {self.path} keys={list(body.keys())} tools={len(body.get('tools') or [])}", flush=True)
        if str(body.get("model", "")).startswith("broken-model"):
            return self._json(500, {"error": {"message": "mock primary model failure"}})
        LAST_OPENAI.clear()
        LAST_OPENAI.update(body)
        system_text = " ".join(str(m.get("content") or "") for m in body.get("messages") or []
                               if m.get("role") == "system")
        if "质量审查" not in system_text and "记忆抽取" not in system_text:
            LAST_CHAT.clear()
            LAST_CHAT.update(body)
        answer = answer_for(body)
        last_user = ""
        for m in body.get("messages") or []:
            if m.get("role") == "user":
                c = m.get("content")
                last_user = c if isinstance(c, str) else str(c)
        slow = "长回答" in last_user
        if slow:
            answer = "长回答：" + "这是模拟流式内容。 " * 40
        if isinstance(answer, dict) and answer.get("tool_call"):
            name = answer["tool_call"]
            if body.get("stream"):
                # OpenAI 流式 tool_calls：Spring AI 需要 delta.tool_calls 才会执行工具
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                tool_chunk = {
                    "id": "chatcmpl-mock-tool", "object": "chat.completion.chunk", "created": 0,
                    "model": body.get("model", "mock-model"),
                    "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [{
                        "index": 0, "id": "call_mock_1", "type": "function",
                        "function": {"name": name, "arguments": "{\"expression\":\"12*3\"}"}
                    }]}, "finish_reason": None}]
                }
                self.wfile.write(f"data: {json.dumps(tool_chunk, ensure_ascii=False)}\n\n".encode())
                finish_chunk = {
                    "id": "chatcmpl-mock-tool", "object": "chat.completion.chunk", "created": 0,
                    "model": body.get("model", "mock-model"),
                    "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]
                }
                self.wfile.write(f"data: {json.dumps(finish_chunk, ensure_ascii=False)}\n\ndata: [DONE]\n\n".encode())
                self.wfile.flush()
                return
            return self._json(200, tool_call_payload(body, name))
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            chunks = [answer[i:i + 12] for i in range(0, len(answer), 12)] or [""]
            first = {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": 0,
                     "model": body.get("model", "mock-model"),
                     "choices": [{"index": 0, "delta": {"role": "assistant", "content": ""},
                                  "finish_reason": None}]}
            self.wfile.write(f"data: {json.dumps(first, ensure_ascii=False)}\n\n".encode())
            for c in chunks:
                chunk = {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": 0,
                         "model": body.get("model", "mock-model"),
                         "choices": [{"index": 0, "delta": {"content": c}, "finish_reason": None}]}
                self.wfile.write(f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n".encode())
                self.wfile.flush()
                if slow:
                    time.sleep(0.2)
            done = {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": 0,
                    "model": body.get("model", "mock-model"),
                    "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 20, "completion_tokens": 8, "total_tokens": 28}}
            self.wfile.write(f"data: {json.dumps(done, ensure_ascii=False)}\n\ndata: [DONE]\n\n".encode())
            return
        return self._json(200, chat_payload(body, answer))

    def log_message(self, *args):
        pass


class OllamaHandler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(read_body(self) or b"{}")
        inputs = body.get("input") or []
        if isinstance(inputs, str):
            inputs = [inputs]
        data = []
        for i, _ in enumerate(inputs):
            vec = [round(((i + 1) * 7 % 97) / 97.0, 6)] * 768
            data.append({"index": i, "embedding": vec})
        self._json(200, {"data": data})

    def log_message(self, *args):
        pass


class CaptureHandler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        raw = read_body(self).decode() or "{}"
        try:
            body = json.loads(raw)
        except Exception:
            body = {"raw": raw}
        CAPTURED.append({"path": self.path, "body": body})
        self._json(200, {"ok": True})

    def do_GET(self):
        if self.path == "/captured":
            return self._json(200, CAPTURED)
        if self.path == "/reset":
            CAPTURED.clear()
            return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})

    def log_message(self, *args):
        pass


class McpHandler(BaseHTTPRequestHandler):
    """MCP Mock：Streamable HTTP（/mcp）+ OAuth 2.1 保护端点（/mcp-oauth）+ 授权服务器。

    /mcp 强制 Mcp-Session-Id 会话（未携带 404）；/mcp-oauth 额外要求 Bearer access token。
    授权码 token 的 expires_in=3，用于验证后端“临期自动刷新”；刷新后的 token expires_in=3600。
    """

    sessions = set()
    codes = {}
    tokens = set()
    refresh_tokens = set()
    stats = {"initialize": 0, "toolsList": 0, "toolsCall": 0,
             "resourcesList": 0, "resourcesRead": 0, "promptsList": 0, "promptsGet": 0,
             "register": 0, "authorize": 0, "token": 0, "refresh": 0}

    def _json(self, code, obj, headers=None):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _origin(self):
        return "http://" + self.headers.get("Host", "127.0.0.1:18093")

    def _mcp_tools(self, rid, params=None):
        McpHandler.stats["toolsList"] += 1
        # /mcp-paged 专门用于验证 nextCursor 分页
        if self.path.startswith("/mcp-diff"):
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {"tools": [{
                "name": "echo2", "description": "新工具",
                "inputSchema": {"type": "object"}
            }]}})
        if self.path.startswith("/mcp-paged"):
            cursor = (params or {}).get("cursor")
            if not cursor:
                return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                    "tools": [{"name": "echo-page1", "description": "第一页",
                               "inputSchema": {"type": "object"}}],
                    "nextCursor": "page2"}})
            if cursor == "page2":
                return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                    "tools": [{"name": "echo-page2", "description": "第二页",
                               "inputSchema": {"type": "object"}}]}})
            return self._json(200, {"jsonrpc": "2.0", "id": rid,
                                    "error": {"code": -32602, "message": "bad cursor"}})
        return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {"tools": [{
            "name": "echo", "description": "回显输入",
            "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
            "outputSchema": {"type": "object", "properties": {"echo": {"type": "string"}}},
            "annotations": {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True}
        }]}})

    def _handle_mcp(self, body):
        method = body.get("method", "")
        rid = body.get("id")
        if method == "initialize":
            McpHandler.stats["initialize"] += 1
            sid = f"sess-{McpHandler.stats['initialize']}"
            McpHandler.sessions.add(sid)
            return self._json(200, {
                "jsonrpc": "2.0", "id": rid,
                "result": {"protocolVersion": "2025-03-26",
                           "capabilities": {"tools": {}, "resources": {"listChanged": False},
                                            "prompts": {"listChanged": False}},
                           "serverInfo": {"name": "mcp-mock", "version": "1.0"}}
            }, headers={"Mcp-Session-Id": sid})
        sid = self.headers.get("Mcp-Session-Id")
        if not sid or sid not in McpHandler.sessions:
            return self._json(404, {"error": "session not found"})
        if method == "notifications/initialized":
            return self._json(200, {})
        if method == "tools/list":
            return self._mcp_tools(rid, body.get("params") or {})
        if method == "tools/call":
            McpHandler.stats["toolsCall"] += 1
            args = (body.get("params") or {}).get("arguments") or {}
            text = str(args.get("text", ""))
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": "echo: " + text}],
                "structuredContent": {"echo": text}
            }})
        if method == "resources/list":
            McpHandler.stats["resourcesList"] += 1
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {"resources": [{
                "uri": "mock://docs/readme", "name": "readme",
                "description": "Mock 文档", "mimeType": "text/plain", "size": 18
            }]}})
        if method == "resources/templates/list":
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "resourceTemplates": [{"uriTemplate": "mock://docs/{id}", "name": "doc",
                                       "description": "Mock 模板", "mimeType": "text/plain"}]
            }})
        if method == "resources/read":
            McpHandler.stats["resourcesRead"] += 1
            uri = str((body.get("params") or {}).get("uri", ""))
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "contents": [{"uri": uri, "mimeType": "text/plain",
                              "text": "resource:" + uri}]
            }})
        if method == "prompts/list":
            McpHandler.stats["promptsList"] += 1
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "prompts": [{"name": "summarize", "description": "Mock 提示词",
                             "arguments": [{"name": "text", "description": "待总结内容",
                                            "required": True}]}]
            }})
        if method == "prompts/get":
            McpHandler.stats["promptsGet"] += 1
            args = (body.get("params") or {}).get("arguments") or {}
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "description": "Mock 提示词结果",
                "messages": [{"role": "user", "content": {
                    "type": "text", "text": "prompt:" + str(args.get("text", ""))}}]
            }})
        self._json(200, {"jsonrpc": "2.0", "id": rid,
                         "error": {"code": -32601, "message": "method not found"}})

    def _issue_tokens(self, expires_in):
        McpHandler.stats["token"] += 1
        at = f"at-{McpHandler.stats['token']}"
        rt = f"rt-{McpHandler.stats['token']}"
        McpHandler.tokens.add(at)
        McpHandler.refresh_tokens.add(rt)
        return {"access_token": at, "refresh_token": rt, "token_type": "Bearer",
                "expires_in": expires_in, "scope": "mcp"}

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/stats":
            body = dict(McpHandler.stats)
            body["activeSessions"] = len(McpHandler.sessions)
            body["activeTokens"] = len(McpHandler.tokens)
            return self._json(200, body)
        if path in ("/.well-known/oauth-protected-resource",
                    "/.well-known/oauth-protected-resource/mcp-oauth"):
            return self._json(200, {
                "resource": self._origin() + "/mcp-oauth",
                "authorization_servers": [self._origin()]})
        if path == "/.well-known/oauth-authorization-server":
            return self._json(200, {
                "issuer": self._origin(),
                "authorization_endpoint": self._origin() + "/oauth/authorize",
                "token_endpoint": self._origin() + "/oauth/token",
                "registration_endpoint": self._origin() + "/oauth/register",
                "code_challenge_methods_supported": ["S256"],
                "token_endpoint_auth_methods_supported": ["none"],
                "scopes_supported": ["mcp"]})
        if path == "/oauth/authorize":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            redirect = (q.get("redirect_uri") or [""])[0]
            if not redirect or (q.get("response_type") or [""])[0] != "code":
                return self._json(400, {"error": "invalid_request"})
            McpHandler.stats["authorize"] += 1
            code = f"code-{McpHandler.stats['authorize']}"
            McpHandler.codes[code] = {
                "challenge": (q.get("code_challenge") or [""])[0],
                "redirect": redirect,
            }
            location = redirect + ("&" if "?" in redirect else "?") + urllib.parse.urlencode({
                "code": code, "state": (q.get("state") or [""])[0]})
            self.send_response(302)
            self.send_header("Location", location)
            self.end_headers()
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        raw = read_body(self)
        if path == "/oauth/register":
            McpHandler.stats["register"] += 1
            return self._json(200, {"client_id": "mock-oauth-client",
                                    "token_endpoint_auth_method": "none"})
        if path == "/oauth/token":
            form = urllib.parse.parse_qs(raw.decode() or "")
            grant = (form.get("grant_type") or [""])[0]
            if grant == "authorization_code":
                code = (form.get("code") or [""])[0]
                entry = McpHandler.codes.pop(code, None)
                verifier = (form.get("code_verifier") or [""])[0]
                if entry is None or _s256(verifier) != entry["challenge"] \
                        or (form.get("redirect_uri") or [""])[0] != entry["redirect"]:
                    return self._json(400, {"error": "invalid_grant"})
                return self._json(200, self._issue_tokens(3))
            if grant == "refresh_token":
                rt = (form.get("refresh_token") or [""])[0]
                if rt not in McpHandler.refresh_tokens:
                    return self._json(400, {"error": "invalid_grant"})
                McpHandler.refresh_tokens.discard(rt)
                McpHandler.stats["refresh"] += 1
                return self._json(200, self._issue_tokens(3600))
            return self._json(400, {"error": "unsupported_grant_type"})
        if path in ("/mcp", "/mcp-oauth", "/mcp-paged", "/mcp-diff"):
            if path == "/mcp-oauth":
                auth = self.headers.get("Authorization", "")
                if not auth.startswith("Bearer ") or auth[7:] not in McpHandler.tokens:
                    return self._json(401, {"error": "invalid_token"}, headers={
                        "WWW-Authenticate": 'Bearer resource_metadata="'
                        + self._origin() + '/.well-known/oauth-protected-resource/mcp-oauth"'})
            body = json.loads(raw or b"{}")
            return self._handle_mcp(body)
        self._json(404, {"error": "not found"})

    def log_message(self, *args):
        pass


def _s256(verifier):
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")


class AnthropicHandler(BaseHTTPRequestHandler):
    """Anthropic 原生协议 Mock：POST /v1/messages + GET /v1/models。"""

    def _json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/v1/models":
            return self._json(200, {"data": [{"id": "claude-mock", "display_name": "Claude Mock"}],
                                    "has_more": False})
        self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if self.path != "/v1/messages":
            return self._json(404, {"error": {"message": "not found"}})
        body = json.loads(read_body(self) or b"{}")
        stream = bool(body.get("stream"))
        text = "Anthropic OK"
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            events = [
                ("message_start", {"type": "message_start", "message": {
                    "id": "msg_mock", "type": "message", "role": "assistant", "model": body.get("model"),
                    "content": [], "stop_reason": None, "usage": {"input_tokens": 100, "output_tokens": 0}}}),
                ("content_block_start", {"type": "content_block_start", "index": 0,
                                         "content_block": {"type": "text", "text": ""}}),
                ("content_block_delta", {"type": "content_block_delta", "index": 0,
                                         "delta": {"type": "text_delta", "text": text}}),
                ("content_block_stop", {"type": "content_block_stop", "index": 0}),
                ("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn"},
                                   "usage": {"output_tokens": 5}}),
                ("message_stop", {"type": "message_stop"}),
            ]
            for name, data in events:
                self.wfile.write(f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode())
                self.wfile.flush()
            return
        self._json(200, {
            "id": "msg_mock", "type": "message", "role": "assistant",
            "model": body.get("model", "claude-mock"),
            "content": [{"type": "text", "text": text}],
            "stop_reason": "end_turn", "stop_sequence": None,
            "usage": {"input_tokens": 100, "output_tokens": 5}
        })

    def log_message(self, *args):
        pass


class OtlpHandler(BaseHTTPRequestHandler):
    """OTLP HTTP/Protobuf collector Mock：只统计请求与字节数，返回空 protobuf 成功响应。"""

    stats = {"requests": 0, "bytes": 0}

    def do_POST(self):
        if self.path != "/v1/traces":
            return self._json(404, {"error": "not found"})
        raw = read_body(self)
        OtlpHandler.stats["requests"] += 1
        OtlpHandler.stats["bytes"] += len(raw or b"")
        self.send_response(200)
        self.send_header("Content-Type", "application/x-protobuf")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if self.path == "/stats":
            return self._json(200, dict(OtlpHandler.stats))
        self._json(404, {"error": "not found"})

    def _json(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def serve(port, handler):
    ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()


if __name__ == "__main__":
    threading.Thread(target=serve, args=(18080, OpenAiHandler), daemon=True).start()
    threading.Thread(target=serve, args=(11434, OllamaHandler), daemon=True).start()
    threading.Thread(target=serve, args=(18093, McpHandler), daemon=True).start()
    threading.Thread(target=serve, args=(18095, AnthropicHandler), daemon=True).start()
    threading.Thread(target=serve, args=(18097, OtlpHandler), daemon=True).start()
    serve(18092, CaptureHandler)
