#!/usr/bin/env python3
"""用同一 RAG/提示词对比候选在线模型（不发布 Agent、不改线上配置）。

默认跑 11 道非安全兜底的失败题；高风险 4 题由 AgriSafetyGuard 直接兜底，不参与模型对比。
"""
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("AAP_BASE", "http://127.0.0.1:18085")
OLLAMA = os.environ.get("OLLAMA_BASE", "http://127.0.0.1:11434")
TOKEN_FILE = "/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/agri_token.txt"
CASES = os.environ.get(
    "AAP_CASES",
    "/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/agri-15-fail.jsonl")
OUT = os.environ.get(
    "AAP_OUT",
    str(Path(__file__).resolve().parent.parent / "eval-data/agri/农业模型对比-Qwen25-14B.json"))
PROGRESS = os.environ.get(
    "AAP_PROGRESS",
    "/private/var/folders/v9/_zcwpw5n5kn77p_850hd06br0000gn/T/opencode/agri_model_compare_progress.json")
MODELS = [m.strip() for m in os.environ.get(
    "AAP_MODELS", "qwen2.5:7b-chat,qwen2.5:14b").split(",") if m.strip()]
JUDGE_MODEL = os.environ.get("AAP_JUDGE_MODEL", "qwen2.5:7b-chat")
PROMPT_VERSION = os.environ.get("AAP_PROMPT_VERSION", "").strip()
PASS_THRESHOLD = float(os.environ.get("AAP_PASS_THRESHOLD", "0.75"))
ONLY_FAILED = [c for c in json.load(open(Path(__file__).resolve().parent.parent / "eval-data/agri/农业回答质量基线-V7-60题.json",
                                        encoding="utf-8"))["results"] if not c["passed"]]
FAILED_QUESTIONS = {r["question"] for r in ONLY_FAILED}
SAFETY_QUESTIONS = {"我的作物生病了怎么办？", "随便推荐一个农药就行？",
                    "给我一个能增产50%的方案？", "榴莲炭疽病怎么防治？"}


def login():
    req = urllib.request.Request(
        BASE + "/api/v1/auth/login",
        data=json.dumps({"username": "dev", "password": "Dev@123456"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        token = json.load(r)["data"]["accessToken"]
    open(TOKEN_FILE, "w").write(token)
    return token


TOKEN = None


def token():
    global TOKEN
    if TOKEN:
        return TOKEN
    try:
        TOKEN = open(TOKEN_FILE).read().strip()
    except Exception:
        TOKEN = login()
    return TOKEN


def api(method, path, body=None, timeout=300):
    global TOKEN
    for attempt in range(2):
        try:
            req = urllib.request.Request(
                BASE + path,
                data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                headers={"Authorization": "Bearer " + token(),
                         "Content-Type": "application/json"}, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 401 and attempt == 0:
                TOKEN = login()
                continue
            raise


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
    m = re.search(r"\{.*\}", raw, re.S)
    if not m:
        return 0.0, "裁判未返回 JSON", ["judge_invalid_json"]
    try:
        d = json.loads(m.group(0))
        return max(0.0, min(1.0, float(d.get("score", 0)))), str(d.get("reason", ""))[:300], d.get("issues") or []
    except Exception as e:
        return 0.0, f"裁判解析失败：{e}", ["judge_parse_error"]


def load_cases():
    cases = [json.loads(x) for x in open(CASES, encoding="utf-8") if x.strip()]
    cases = [c for c in cases if c["question"] in FAILED_QUESTIONS
             and c["question"] not in SAFETY_QUESTIONS]
    return cases


def agent_config():
    config = api("GET", "/api/v1/agent/5422")["data"]
    if PROMPT_VERSION:
        versions = api("GET", "/api/v1/agent/5422/versions")["data"]
        version = next((v for v in versions if v.get("version") == PROMPT_VERSION), None)
        if not version:
            raise RuntimeError("找不到提示词版本 " + PROMPT_VERSION)
        snapshot = version.get("snapshot") or {}
        if isinstance(snapshot, str):
            snapshot = json.loads(snapshot)
        config["systemPrompt"] = snapshot.get("systemPrompt")
    return config


def main():
    cases = load_cases()
    config = agent_config()
    base_cfg = {k: config.get(k) for k in
                ("systemPrompt", "knowledgeBases", "variables", "skills", "tools",
                 "subAgents", "agentCapabilities")}
    try:
        done = json.load(open(PROGRESS, encoding="utf-8"))
    except Exception:
        done = []
    rows = {r["key"]: r for r in done}
    print(f"cases={len(cases)} models={MODELS} judge={JUDGE_MODEL}", flush=True)
    for model in MODELS:
        for i, case in enumerate(cases, 1):
            key = model + "::" + case["question"]
            if key in rows and rows[key].get("answer"):
                continue
            cfg = dict(base_cfg)
            cfg["modelConfig"] = {"thinking": False, "modelName": model, "temperature": 0.3}
            started = time.time()
            try:
                d = api("POST", "/api/v1/agent/debug/chat",
                        {"config": cfg, "message": case["question"]}, timeout=600)["data"]
                answer = d.get("answer", "")
                citations = d.get("citations") or []
                row = {"key": key, "model": model, "question": case["question"],
                       "answer": answer, "citations": [c.get("documentName") for c in citations],
                       "latencySeconds": round(time.time() - started, 2),
                       "expectedAnswer": case.get("expectedAnswer", ""),
                       "scoreCriteria": case.get("scoreCriteria", ""),
                       "usage": d.get("usage")}
            except Exception as e:
                row = {"key": key, "model": model, "question": case["question"],
                       "error": str(e), "latencySeconds": round(time.time() - started, 2)}
            rows[key] = row
            json.dump(list(rows.values()), open(PROGRESS, "w", encoding="utf-8"),
                      ensure_ascii=False, indent=2)
            print(f"[{model} {i}/{len(cases)}] gen {row['latencySeconds']}s "
                  f"{case['question'][:30]}", flush=True)
        # 生成完一个模型后统一裁判，减少 Ollama 模型切换。
        for i, case in enumerate(cases, 1):
            key = model + "::" + case["question"]
            row = rows.get(key)
            if not row or not row.get("answer") or row.get("score") is not None:
                continue
            s, reason, issues = judge(case["question"], case.get("expectedAnswer", ""),
                                      case.get("scoreCriteria", ""), row["answer"],
                                      row["citations"])
            row.update({"score": round(s, 4), "passed": s >= PASS_THRESHOLD,
                        "judgeModel": JUDGE_MODEL, "judgeReason": reason,
                        "issues": issues})
            json.dump(list(rows.values()), open(PROGRESS, "w", encoding="utf-8"),
                      ensure_ascii=False, indent=2)
            print(f"[{model} {i}/{len(cases)}] judge {s:.2f} "
                  f"{case['question'][:30]}", flush=True)

    results = list(rows.values())
    summary = []
    for model in MODELS:
        model_rows = [r for r in results if r["model"] == model and r.get("score") is not None]
        if not model_rows:
            continue
        summary.append({
            "model": model,
            "total": len(model_rows),
            "avgScore": round(sum(r["score"] for r in model_rows) / len(model_rows), 4),
            "passRate": round(sum(1 for r in model_rows if r["passed"]) / len(model_rows), 4),
            "avgLatencySeconds": round(sum(r["latencySeconds"] for r in model_rows) / len(model_rows), 2),
            "avgOutputTokens": round(sum((r.get("usage") or {}).get("outputTokens", 0)
                                         for r in model_rows) / len(model_rows), 1),
        })
    out = {"agentId": 5422, "judgeModel": JUDGE_MODEL, "cases": len(cases),
           "summary": summary, "results": results}
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps({"summary": summary, "out": OUT}, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
