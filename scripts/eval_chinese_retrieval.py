#!/usr/bin/env python3
"""固定中文检索黄金集的 Recall@K / MRR 评测。

只依赖 Python 标准库，直接调用 OpenAI 兼容或 Ollama 的 embedding 接口：

  python3 scripts/eval_chinese_retrieval.py \
    --endpoint http://127.0.0.1:11434/v1/embeddings \
    --model bge-m3

默认读取 docs/fixtures/中文检索黄金集-v1.json。该脚本不访问数据库，
适合在切换 embedding 模型、调整分块或升级向量库前后做同口径对比。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Iterable


def post_json(url: str, payload: dict, timeout: float, api_key: str | None) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"HTTP {error.code} {url}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"无法连接 embedding 服务 {url}: {error.reason}") from error


def endpoints(endpoint: str) -> list[str]:
    value = endpoint.rstrip("/")
    if value.endswith("/embeddings") or value.endswith("/api/embed"):
        return [value]
    if value.endswith("/v1"):
        return [value + "/embeddings"]
    return [value + "/v1/embeddings", value + "/api/embed", value + "/api/embeddings"]


def parse_embeddings(payload: dict) -> list[list[float]]:
    if isinstance(payload.get("embeddings"), list):
        return [[float(x) for x in row] for row in payload["embeddings"]]
    data = payload.get("data")
    if isinstance(data, list):
        if data and isinstance(data[0], dict):
            ordered = sorted(data, key=lambda item: item.get("index", 0))
            return [[float(x) for x in item["embedding"]] for item in ordered]
        return [[float(x) for x in row] for row in data]
    if isinstance(payload.get("embedding"), list):
        return [[float(x) for x in payload["embedding"]]]
    raise RuntimeError(f"无法识别的 embedding 响应：{str(payload)[:300]}")


def embed(texts: list[str], endpoint: str, model: str, timeout: float,
          api_key: str | None, batch_size: int) -> list[list[float]]:
    errors: list[str] = []
    for url in endpoints(endpoint):
        try:
            vectors: list[list[float]] = []
            for start in range(0, len(texts), batch_size):
                batch = texts[start:start + batch_size]
                # OpenAI 兼容接口使用 input；Ollama /api/embed 使用 input（数组），旧版用 prompt。
                payload = {"model": model, "input": batch}
                try:
                    result = post_json(url, payload, timeout, api_key)
                except RuntimeError as error:
                    if "/api/embeddings" not in url or len(batch) != 1:
                        raise
                    result = post_json(url, {"model": model, "prompt": batch[0]}, timeout, api_key)
                part = parse_embeddings(result)
                if len(part) != len(batch):
                    raise RuntimeError(f"embedding 返回数量不匹配：期望 {len(batch)}，实际 {len(part)}")
                vectors.extend(part)
            return vectors
        except Exception as error:  # 尝试下一个兼容端点
            errors.append(f"{url}: {error}")
    raise RuntimeError("所有 embedding 端点均失败：\n" + "\n".join(errors))


def cosine(a: list[float], b: list[float]) -> float:
    if len(a) != len(b):
        raise RuntimeError(f"向量维度不一致：{len(a)} vs {len(b)}")
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return 0.0 if na == 0 or nb == 0 else dot / (na * nb)


def dcg(relevances: Iterable[float]) -> float:
    return sum((2 ** rel - 1) / math.log2(i + 2)
               for i, rel in enumerate(relevances))


def evaluate(fixture: dict, doc_vectors: list[list[float]],
             query_vectors: list[list[float]], top_k: int) -> dict:
    documents = fixture["documents"]
    queries = fixture["queries"]
    rows = []
    for query, vector in zip(queries, query_vectors):
        scored = sorted(
            ((cosine(vector, doc_vector), document["id"])
             for document, doc_vector in zip(documents, doc_vectors)),
            reverse=True,
        )
        ranked = [doc_id for _, doc_id in scored]
        relevant = set(query["relevant"])
        first_rank = next((i + 1 for i, doc_id in enumerate(ranked) if doc_id in relevant), None)
        recalls = {k: sum(1 for doc_id in ranked[:k] if doc_id in relevant) / len(relevant)
                   for k in (1, 3, 5, 10)}
        binary = [1.0 if doc_id in relevant else 0.0 for doc_id in ranked[:top_k]]
        ideal = [1.0] * min(len(relevant), top_k) + [0.0] * max(0, top_k - len(relevant))
        rows.append({
            "id": query["id"],
            "query": query["query"],
            "expected": sorted(relevant),
            "ranked": ranked[:top_k],
            "firstRank": first_rank,
            "mrr": 0.0 if first_rank is None else 1.0 / first_rank,
            **{f"recall@{k}": value for k, value in recalls.items()},
            "ndcg@5": dcg(binary[:5]) / dcg(ideal[:5]) if dcg(ideal[:5]) else 0.0,
        })
    count = max(1, len(rows))
    return {
        "queries": len(rows),
        "documents": len(documents),
        "recall@1": sum(row["recall@1"] for row in rows) / count,
        "recall@3": sum(row["recall@3"] for row in rows) / count,
        "recall@5": sum(row["recall@5"] for row in rows) / count,
        "recall@10": sum(row["recall@10"] for row in rows) / count,
        "mrr": sum(row["mrr"] for row in rows) / count,
        "ndcg@5": sum(row["ndcg@5"] for row in rows) / count,
        "details": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", default="docs/fixtures/中文检索黄金集-v1.json")
    parser.add_argument("--endpoint", default=os.getenv("EMBEDDING_ENDPOINT",
                                                        "http://127.0.0.1:11434/v1/embeddings"))
    parser.add_argument("--model", default=os.getenv("EMBEDDING_MODEL", "bge-m3"))
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--api-key", default=os.getenv("EMBEDDING_API_KEY"))
    parser.add_argument("--output", help="同时写入 JSON 结果文件")
    parser.add_argument("--min-recall5", type=float, default=None,
                        help="设置后 Recall@5 低于该值则退出码 1")
    parser.add_argument("--min-mrr", type=float, default=None,
                        help="设置后 MRR 低于该值则退出码 1")
    args = parser.parse_args()

    fixture_path = Path(args.fixture)
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    documents = fixture.get("documents") or []
    queries = fixture.get("queries") or []
    if not documents or not queries:
        raise SystemExit("fixture 必须包含非空 documents/queries")
    ids = [doc["id"] for doc in documents]
    if len(ids) != len(set(ids)):
        raise SystemExit("fixture documents.id 必须唯一")
    for query in queries:
        unknown = set(query.get("relevant") or []) - set(ids)
        if unknown:
            raise SystemExit(f"查询 {query.get('id')} 引用了未知文档：{sorted(unknown)}")

    doc_texts = [f"{doc.get('title', '')}\n{doc.get('text', '')}" for doc in documents]
    query_texts = [query["query"] for query in queries]
    doc_vectors = embed(doc_texts, args.endpoint, args.model, args.timeout,
                        args.api_key, max(1, args.batch_size))
    query_vectors = embed(query_texts, args.endpoint, args.model, args.timeout,
                          args.api_key, max(1, args.batch_size))
    result = evaluate(fixture, doc_vectors, query_vectors, args.top_k)
    result.update({"model": args.model, "endpoint": args.endpoint,
                   "fixture": str(fixture_path)})

    print(f"{fixture.get('name', fixture_path.name)}  model={args.model}")
    print(f"{'query':<36} {'first':>5} {'recall@1':>9} {'recall@5':>9} {'mrr':>7}")
    for row in result["details"]:
        print(f"{row['query']:<36} {str(row['firstRank'] or '-'):>5} "
              f"{row['recall@1']:>9.3f} {row['recall@5']:>9.3f} {row['mrr']:>7.3f}")
    print(f"\nRecall@1={result['recall@1']:.3f}  Recall@3={result['recall@3']:.3f}  "
          f"Recall@5={result['recall@5']:.3f}  Recall@10={result['recall@10']:.3f}  "
          f"MRR={result['mrr']:.3f}  nDCG@5={result['ndcg@5']:.3f}")

    if args.output:
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
        print(f"结果已写入 {args.output}")

    failed = False
    if args.min_recall5 is not None and result["recall@5"] < args.min_recall5:
        print(f"Recall@5 {result['recall@5']:.3f} < 门禁 {args.min_recall5}", file=sys.stderr)
        failed = True
    if args.min_mrr is not None and result["mrr"] < args.min_mrr:
        print(f"MRR {result['mrr']:.3f} < 门禁 {args.min_mrr}", file=sys.stderr)
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
