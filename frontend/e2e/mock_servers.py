#!/usr/bin/env python3
"""离线 Mock：OpenAI 兼容对话(18080) + Ollama 嵌入(11434) + 渠道出站捕获(18092)。

供 E2E 在没有真实模型/嵌入/渠道时跑通核心链路；仅用于测试环境。
"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CAPTURED = []
LAST_OPENAI = {}


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
    if "只回答OK" in system or "只回答OK" in last_user:
        return "OK"
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
        if self.path.endswith("/models"):
            return self._json(200, {"data": [{"id": "mock-model", "name": "Mock Model"}]})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.endswith("/chat/completions"):
            return self._json(404, {"error": "not found"})
        body = json.loads(read_body(self) or b"{}")
        print(f"[openai] {self.path} keys={list(body.keys())} tools={len(body.get('tools') or [])}", flush=True)
        LAST_OPENAI.clear()
        LAST_OPENAI.update(body)
        answer = answer_for(body)
        if isinstance(answer, dict) and answer.get("tool_call"):
            return self._json(200, tool_call_payload(body, answer["tool_call"]))
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


def serve(port, handler):
    ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()


if __name__ == "__main__":
    threading.Thread(target=serve, args=(18080, OpenAiHandler), daemon=True).start()
    threading.Thread(target=serve, args=(11434, OllamaHandler), daemon=True).start()
    serve(18092, CaptureHandler)
