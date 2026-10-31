#!/usr/bin/env python3
"""本地工作流并发稳定性测试。

并发调用已发布的本地工作流，验证：
  - 请求成功率
  - 节点链路是否完整
  - P50/P95 延迟
  - 失败样本

默认针对 Agent 4878 的订单分支（意图识别 + 订单回复 2 次本地模型调用），
使用本机 Ollama qwen2.5:7b，不使用 Mock。

用法：
  python3 scripts/local-workflow-concurrency.py
  python3 scripts/local-workflow-concurrency.py --runs 8 --concurrency 4
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed


def call(method: str, url: str, token: str | None = None,
         body: dict | None = None, timeout: int = 300):
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
    parser.add_argument("--agent-id", type=int, default=int(os.getenv("LOCAL_WORKFLOW_AGENT_ID", "4878")))
    parser.add_argument("--username", default=os.getenv("PLATFORM_USER", "dev"))
    parser.add_argument("--password", default=os.getenv("PLATFORM_PASSWORD", "Dev@123456"))
    parser.add_argument("--runs", type=int, default=8)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--input", default=os.getenv("LOCAL_WORKFLOW_INPUT", "我的订单什么时候发货？"))
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    if args.runs < 1 or args.concurrency < 1:
        raise RuntimeError("runs/concurrency 必须大于 0")

    base = args.base_url.rstrip("/") + "/api/v1"
    login = call("POST", base + "/auth/login", body={
        "username": args.username, "password": args.password
    })
    token = login.get("accessToken")
    if not token:
        raise RuntimeError("登录失败：未返回 accessToken")

    def run_one(index: int):
        started = time.perf_counter()
        try:
            data = call("POST", base + f"/agent/{args.agent_id}/workflow/run", token,
                        {"input": args.input}, timeout=args.timeout)
            elapsed = (time.perf_counter() - started) * 1000
            names = [str(x.get("name") or x.get("nodeId")) for x in (data.get("nodeRuns") or [])]
            ok = (data.get("status") == "SUCCESS"
                  and "订单回复" in names
                  and bool(str(data.get("output") or "").strip()))
            return index, ok, elapsed, data.get("status"), names, data.get("output"), None
        except Exception as e:
            elapsed = (time.perf_counter() - started) * 1000
            return index, False, elapsed, "ERROR", [], "", str(e)

    started = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(run_one, i) for i in range(args.runs)]
        for future in as_completed(futures):
            results.append(future.result())
    total = (time.perf_counter() - started) * 1000

    results.sort(key=lambda x: x[0])
    latencies = sorted(r[2] for r in results)
    ok_count = sum(1 for r in results if r[1])
    p50 = statistics.median(latencies) if latencies else 0
    idx95 = min(len(latencies) - 1, max(0, int(round(0.95 * (len(latencies) - 1)))))
    p95 = latencies[idx95] if latencies else 0
    print(f"agent={args.agent_id} input={args.input!r}")
    print(f"runs={args.runs} concurrency={args.concurrency} success={ok_count}/{args.runs}")
    print(f"wall={total:.0f}ms p50={p50:.0f}ms p95={p95:.0f}ms max={max(latencies):.0f}ms")
    for r in results:
        index, ok, elapsed, status, names, output, error = r
        if not ok:
            print(f"  FAIL #{index} {elapsed:.0f}ms status={status} nodes={names} error={error or output}",
                  file=sys.stderr)
        else:
            print(f"  ok #{index} {elapsed:.0f}ms {' → '.join(names)}")
    if ok_count != args.runs:
        print(f"LOCAL-WORKFLOW-CONCURRENCY FAIL ({ok_count}/{args.runs})", file=sys.stderr)
        return 1
    print("LOCAL-WORKFLOW-CONCURRENCY PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"失败：{e}", file=sys.stderr)
        raise SystemExit(1)
