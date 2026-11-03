#!/usr/bin/env python3
"""农业回答质量评测 runner。

特点：
- 逐题调用已发布 Agent，避免平台长任务被业务 Pod 滚动发布打断；
- 每题执行后立即落盘，支持断点续跑；
- 使用本地 Ollama 裁判按 expectedAnswer + scoreCriteria 打分；
- 输出每题耗时、tokens、引用和裁判意见，便于定位模型串段问题。

用法：
  python3 scripts/eval-agri-answer-quality.py --limit 10
  python3 scripts/eval-agri-answer-quality.py
"""
import argparse
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.request
from datetime import datetime

BASE = os.environ.get("AAP_BASE", "http://127.0.0.1:18085")
OLLAMA = os.environ.get("OLLAMA_BASE", "http://192.168.64.1:11434")
TOKEN_FILE = "/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/agri_token.txt"
CASES = os.environ.get(
    "AAP_CASES",
    "/Users/wangpan/.zcode/workspace/default/ai-agent-platform/docs/农业回答质量评测集-60题.jsonl")
OUT = os.environ.get(
    "AAP_OUT",
    "/Users/wangpan/.zcode/workspace/default/ai-agent-platform/docs/农业回答质量基线-V7-60题.json")
PROGRESS = os.environ.get(
    "AAP_PROGRESS",
    "/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/agri_quality_v7_progress.json")
AGENT_ID = int(os.environ.get("AAP_AGENT_ID", "5422"))
JUDGE_MODEL = os.environ.get("AAP_JUDGE_MODEL", "qwen2.5:7b-chat")
PASS_THRESHOLD = float(os.environ.get("AAP_PASS_THRESHOLD", "0.75"))


def login():
    req = urllib.request.Request(
        BASE + "/api/v1/auth/login",
        data=json.dumps({"username": "dev", "password": "Dev@123456"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        token = json.load(r)["data"]["accessToken"]
    open(TOKEN_FILE, "w").write(token)
    return token


def token():
    try:
        return open(TOKEN_FILE).read().strip()
    except Exception:
        return login()


def api(method, path, body=None, timeout=600):
    global TOKEN
    for attempt in range(3):
        try:
            req = urllib.request.Request(
                BASE + path,
                data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"},
                method=method)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 401 and attempt == 0:
                TOKEN = login()
                continue
            raise
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2 + attempt * 3)


def judge(question, expected, criteria, answer, citations):
    prompt = f"""你是严格的农业问答质量评审员。请根据“问题、期望答案、评分标准、实际回答”打分。
评分重点：事实正确、覆盖关键措施、引用/来源一致、没有跨作物或跨病害串段、没有编造剂量或承诺。
只输出一个 JSON 对象，不要 Markdown：
{{"score":0到1的小数,"reason":"不超过80字","issues":["问题1","问题2"]}}

问题：
{question}

期望答案：
{expected}

评分标准：
{criteria}

实际回答：
{answer[:8000]}

引用文档：
{json.dumps(citations, ensure_ascii=False)[:2000]}
"""
    body = {"model": JUDGE_MODEL, "prompt": prompt, "stream": False,
            "format": "json", "options": {"temperature": 0.1}}
    req = urllib.request.Request(OLLAMA + "/api/generate",
                                 data=json.dumps(body, ensure_ascii=False).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=300, context=ssl._create_unverified_context()) as r:
        raw = json.load(r).get("response", "")
    text = raw.strip()
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return 0.0, "裁判未返回 JSON", ["judge_invalid_json"]
    try:
        d = json.loads(m.group(0))
        score = max(0.0, min(1.0, float(d.get("score", 0))))
        return score, str(d.get("reason", ""))[:300], d.get("issues") or []
    except Exception as e:
        return 0.0, f"裁判解析失败：{e}", ["judge_parse_error"]


def load_cases():
    return [json.loads(x) for x in open(CASES, encoding="utf-8") if x.strip()]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()
    cases = load_cases()
    if args.limit:
        cases = cases[:args.limit]
    try:
        done = json.load(open(PROGRESS, encoding="utf-8"))
    except Exception:
        done = []
    seen = {d["question"] for d in done}
    print(f"cases={len(cases)} already={len(done)} judge={JUDGE_MODEL}", flush=True)
    for idx, case in enumerate(cases, 1):
        q = case["question"]
        if q in seen:
            continue
        started = time.time()
        try:
            conv = api("POST", "/api/v1/conversations",
                       {"agentId": AGENT_ID, "title": f"质量评测-{idx}"})
            cid = conv["data"]["id"]
            resp = api("POST", f"/api/v1/conversations/{cid}/messages",
                       {"message": q, "stream": False}, timeout=600)
            data = resp.get("data") or {}
            ans = data.get("answer") or ""
            citations = data.get("citations") or []
            score, reason, issues = judge(q, case.get("expectedAnswer", ""),
                                          case.get("scoreCriteria", ""), ans, citations)
            row = {
                "index": idx, "question": q, "answer": ans,
                "expectedAnswer": case.get("expectedAnswer", ""),
                "scoreCriteria": case.get("scoreCriteria", ""),
                "citations": [c.get("documentName") for c in citations],
                "score": round(score, 4), "passed": score >= PASS_THRESHOLD,
                "judgeReason": reason, "issues": issues,
                "latencySeconds": round(time.time() - started, 2),
                "usage": data.get("usage"),
                "conversationId": cid,
            }
            done.append(row)
            json.dump(done, open(PROGRESS, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
            print(f"[{len(done)}/{len(cases)}] {score:.2f} {q[:35]} {row['latencySeconds']}s", flush=True)
        except Exception as e:
            print(f"[{idx}/{len(cases)}] ERROR {q[:35]}: {e}", flush=True)
            time.sleep(5)

    valid = [r for r in done if r.get("score") is not None]
    passed = [r for r in valid if r.get("passed")]
    summary = {
        "agentId": AGENT_ID,
        "model": "qwen2.5:7b-chat",
        "judgeModel": JUDGE_MODEL,
        "passThreshold": PASS_THRESHOLD,
        "total": len(cases),
        "completed": len(done),
        "avgScore": round(sum(r["score"] for r in valid) / len(valid), 4) if valid else None,
        "passRate": round(len(passed) / len(valid), 4) if valid else None,
        "results": done,
    }
    json.dump(summary, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps({k: summary[k] for k in
                      ("total", "completed", "avgScore", "passRate", "judgeModel")},
                     ensure_ascii=False), flush=True)
    print("OUT", OUT, flush=True)


if __name__ == "__main__":
    main()
