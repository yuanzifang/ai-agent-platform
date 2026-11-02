#!/usr/bin/env python3
"""Qwen3.8 27B 直连长稳测试（Ollama /api/generate）。

用途：验证模型服务在持续单并发请求下的可用性、延迟漂移和内存趋势。
注意：这是模型级验收，不等同于平台 HTTP/SSE/RAG 的全链路长稳。
"""
import json
import os
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

OLLAMA = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434")
MODEL = os.environ.get("QWEN_MODEL", "qwen3.8:27b")
DURATION = int(os.environ.get("SOAK_DURATION_SECONDS", "1800"))
PROMPT = os.environ.get("SOAK_PROMPT", "请用一句中文回答：本地模型长稳测试正常。")
OUT = os.environ.get("SOAK_OUTPUT", "/tmp/qwen38-soak-result.json")
REQUEST_TIMEOUT = int(os.environ.get("SOAK_REQUEST_TIMEOUT", "180"))


def rss_mb():
    """Sum RSS of ollama processes; best-effort snapshot for trend."""
    try:
        out = subprocess.check_output(["ps", "-axo", "rss=,command="], text=True)
        total = 0
        for line in out.splitlines():
            if "ollama" in line:
                parts = line.strip().split(None, 1)
                if parts and parts[0].isdigit():
                    total += int(parts[0])
        return round(total / 1024, 1)
    except Exception:
        return None


def one_request():
    payload = json.dumps({
        "model": MODEL,
        "prompt": PROMPT,
        "stream": False,
        "options": {"num_predict": 512, "temperature": 0.1},
    }).encode()
    req = urllib.request.Request(
        f"{OLLAMA}/api/generate", data=payload,
        headers={"Content-Type": "application/json"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as response:
            body = response.read()
            status = response.status
        elapsed = time.monotonic() - started
        data = json.loads(body)
        text = str(data.get("response") or "").strip()
        thinking = str(data.get("thinking") or "")
        eval_count = int(data.get("eval_count") or 0)
        ok = status == 200 and bool(text) and bool(data.get("done"))
        return {
            "ok": ok,
            "status": status,
            "elapsed_ms": round(elapsed * 1000, 1),
            "eval_count": eval_count,
            "response": text[:120],
            "thinking_chars": len(thinking),
            "done_reason": data.get("done_reason"),
        }
    except Exception as exc:
        return {
            "ok": False,
            "status": None,
            "elapsed_ms": round((time.monotonic() - started) * 1000, 1),
            "error": f"{type(exc).__name__}: {exc}",
        }


def main():
    started_at = datetime.now(timezone.utc)
    deadline = time.monotonic() + DURATION
    results = []
    rss = []
    next_progress = time.monotonic() + 60
    print(f"soak start model={MODEL} duration={DURATION}s prompt={PROMPT!r}", flush=True)
    while time.monotonic() < deadline:
        result = one_request()
        results.append(result)
        if len(results) == 1 or len(results) % 10 == 0:
            rss.append({"at": round(time.time(), 3), "rss_mb": rss_mb()})
        if not result["ok"]:
            print(f"FAIL #{len(results)} {result}", flush=True)
        now = time.monotonic()
        if now >= next_progress:
            print(f"progress requests={len(results)} ok={sum(r['ok'] for r in results)} "
                  f"last_ms={result['elapsed_ms']} rss_mb={rss[-1]['rss_mb'] if rss else '-'}",
                  flush=True)
            next_progress += 60
    finished_at = datetime.now(timezone.utc)
    latencies = [r["elapsed_ms"] for r in results]
    successes = [r for r in results if r["ok"]]
    summary = {
        "model": MODEL,
        "prompt": PROMPT,
        "started_at": started_at.isoformat(),
        "finished_at": finished_at.isoformat(),
        "configured_duration_seconds": DURATION,
        "requests": len(results),
        "success": len(successes),
        "failures": len(results) - len(successes),
        "success_rate": round(len(successes) / len(results), 4) if results else 0,
        "latency_ms": {
            "avg": round(statistics.mean(latencies), 1) if latencies else None,
            "p50": round(statistics.median(latencies), 1) if latencies else None,
            "p95": round(sorted(latencies)[max(0, int(len(latencies) * 0.95) - 1)], 1) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
        "eval_tokens": sum(r.get("eval_count", 0) for r in successes),
        "rss_mb_max": max((x["rss_mb"] for x in rss if x["rss_mb"] is not None), default=None),
        "rss_samples": rss,
        "failures_detail": [r for r in results if not r["ok"]],
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"summary": summary, "results": results}, fh, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    print(f"result written to {OUT}", flush=True)


if __name__ == "__main__":
    main()
