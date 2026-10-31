#!/usr/bin/env python3
"""GraphRAG 对照组评测：同一知识库/模型，分别 graphEnabled=true/false。

用法：
  python3 scripts/eval-graphrag.py --kb-id 161 --graph off --query "退款多久到账"
  python3 scripts/eval-graphrag.py --kb-id 161 --graph on --queries-file queries.json

默认调用 /agent/debug/chat，不创建/修改 Agent、知识库或文档。
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request


def call(method: str, url: str, token: str | None = None, body: dict | None = None,
         timeout: int = 180):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
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
    parser.add_argument("--kb-id", type=int, required=True)
    parser.add_argument("--model", default=os.getenv("GRAPHRAG_EVAL_MODEL", "deepseek-flash"))
    parser.add_argument("--graph", choices=["on", "off"], required=True)
    parser.add_argument("--query", action="append", default=[])
    parser.add_argument("--queries-file", help="JSON 数组或每行一个查询")
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()
    if not args.query and not args.queries_file:
        parser.error("至少提供 --query 或 --queries-file")
    queries = list(args.query)
    if args.queries_file:
        raw = open(args.queries_file, encoding="utf-8").read()
        try:
            loaded = json.loads(raw)
            if isinstance(loaded, list):
                queries.extend(str(x) for x in loaded)
            else:
                queries.append(str(loaded))
        except json.JSONDecodeError:
            queries.extend(line.strip() for line in raw.splitlines() if line.strip())
    base = args.base_url.rstrip("/") + "/api/v1"
    login = call("POST", base + "/auth/login", body={
        "username": args.username, "password": args.password
    })
    token = login.get("accessToken")
    if not token:
        raise RuntimeError("登录失败：未返回 accessToken")

    config = {
        "modelConfig": {"modelName": args.model, "temperature": 0.1},
        "systemPrompt": "你是知识库问答助手。只根据检索内容回答；不知道就说明不知道。",
        "knowledgeBases": [{
            "knowledgeBaseId": args.kb_id,
            "enabled": True,
            "retrievalConfig": {
                "topK": args.top_k,
                "scoreThreshold": 0.1,
                "mode": "hybrid",
                "graphEnabled": args.graph == "on"
            }
        }]
    }
    rows = []
    for query in queries:
        started = time.perf_counter()
        data = call("POST", base + "/agent/debug/chat", token,
                    {"config": config, "message": query}, timeout=240)
        elapsed = (time.perf_counter() - started) * 1000
        citations = data.get("citations") or []
        graph_hits = 0
        for c in citations:
            meta = c.get("metadata")
            if isinstance(meta, str):
                try:
                    meta = json.loads(meta)
                except Exception:
                    meta = {}
            if isinstance(meta, dict) and meta.get("graph") is True:
                graph_hits += 1
        rows.append({
            "query": query,
            "latencyMs": round(elapsed),
            "citationCount": len(citations),
            "graphHits": graph_hits,
            "answer": data.get("answer") or "",
        })
        print(f"graph={args.graph} latency={elapsed:.0f}ms citations={len(citations)} "
              f"graphHits={graph_hits} query={query!r}", file=sys.stderr)
    latencies = [r["latencyMs"] for r in rows]
    summary = {
        "graph": args.graph,
        "kbId": args.kb_id,
        "model": args.model,
        "queries": len(rows),
        "avgLatencyMs": round(statistics.mean(latencies)) if latencies else 0,
        "p95LatencyMs": sorted(latencies)[max(0, int(round(0.95 * (len(latencies) - 1))))] if latencies else 0,
        "avgCitations": round(statistics.mean([r["citationCount"] for r in rows]), 2) if rows else 0,
        "graphHits": sum(r["graphHits"] for r in rows),
        "rows": rows,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        raise SystemExit(1)
