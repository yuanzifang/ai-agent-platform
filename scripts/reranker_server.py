#!/usr/bin/env python3
"""本地 BGE Reranker HTTP 服务（供平台 RerankService 调用）。

协议：
  POST /rerank
  {"model":"bge-reranker-v2-m3","query":"...","documents":["..."],"top_n":3}
  -> {"results":[{"index":1,"relevance_score":0.92}, ...]}

启动示例：
  python scripts/reranker_server.py --host 0.0.0.0 --port 8010 \
      --model BAAI/bge-reranker-v2-m3
"""
import argparse
import json
import os
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import torch
from sentence_transformers import CrossEncoder

MODEL = None
MODEL_NAME = "BAAI/bge-reranker-v2-m3"


def load_model(name: str):
    device = os.environ.get("RERANKER_DEVICE", "cpu")
    print(f"loading reranker model={name} device={device}", flush=True)
    # bge-reranker-v2-m3 目前可直接由 sentence-transformers 加载；失败时由启动命令显式换 base 模型。
    max_length = int(os.environ.get("RERANKER_MAX_LENGTH", "2048"))
    model = CrossEncoder(name, device=device, max_length=max_length)
    print("reranker model ready", flush=True)
    return model


class Handler(BaseHTTPRequestHandler):
    server_version = "agri-reranker/1.0"

    def _json(self, code: int, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/health", "/healthz"):
            self._json(200, {"status": "ok", "model": MODEL_NAME})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/rerank":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 5_000_000:
                self._json(400, {"error": "invalid request body"})
                return
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            query = str(body.get("query") or "").strip()
            documents = body.get("documents") or []
            if not query or not isinstance(documents, list) or not documents:
                self._json(400, {"error": "query/documents required"})
                return
            documents = [str(d) for d in documents[:100]]
            top_n = max(1, min(int(body.get("top_n") or len(documents)), len(documents)))
            pairs = [(query, doc) for doc in documents]
            scores = MODEL.predict(pairs, batch_size=8, show_progress_bar=False)
            ranked = sorted(enumerate(float(s) for s in scores), key=lambda x: x[1], reverse=True)
            self._json(200, {
                "model": MODEL_NAME,
                "results": [
                    {"index": idx, "relevance_score": score}
                    for idx, score in ranked[:top_n]
                ],
            })
        except Exception as e:
            traceback.print_exc()
            self._json(500, {"error": str(e)})

    def log_message(self, fmt, *args):
        sys.stderr.write("[reranker] " + fmt % args + "\n")


def main():
    global MODEL, MODEL_NAME
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8010)
    parser.add_argument("--model", default=MODEL_NAME)
    args = parser.parse_args()
    MODEL_NAME = args.model
    MODEL = load_model(args.model)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"listening on http://{args.host}:{args.port}/rerank", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
