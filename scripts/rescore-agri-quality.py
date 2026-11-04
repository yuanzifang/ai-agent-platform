#!/usr/bin/env python3
"""用独立裁判模型复核已有农业回答（不重新生成回答）。"""
import json
import os
import re
import ssl
import time
import urllib.request
from pathlib import Path

SRC = os.environ.get(
    "AAP_SRC",
    str(Path(__file__).resolve().parent.parent / "eval-data/agri/农业回答质量基线-V7-60题.json"))
OUT = os.environ.get(
    "AAP_OUT",
    str(Path(__file__).resolve().parent.parent / "eval-data/agri/农业回答质量交叉验证-Qwen38-15题.json"))
MODEL = os.environ.get("AAP_JUDGE_MODEL", "qwen3.8:27b")
OLLAMA = os.environ.get("OLLAMA_BASE", "http://127.0.0.1:11434")
LIMIT = int(os.environ.get("AAP_LIMIT", "15"))
ONLY_FAILED = os.environ.get("AAP_ONLY_FAILED", "1") == "1"


def judge(question, expected, criteria, answer, citations):
    prompt = f"""你是独立农业问答质量复核员。只输出一个 JSON 对象，不要 Markdown：
{{"score":0到1的小数,"reason":"不超过100字","issues":["问题1","问题2"]}}

评分要求：
- 必须针对问题中的作物和病害；
- 出现其他作物或其他病害的防治措施，最高 0.7；
- 无依据推荐农药、承诺产量/治愈率，最高 0.5；
- 内容过长但不偏离，不应仅因长度扣到不合格；
- 事实正确、措施完整、有安全提示才可高于 0.85。

问题：{question}
期望答案：{expected}
评分标准：{criteria}
实际回答：{answer[:8000]}
引用：{json.dumps(citations, ensure_ascii=False)[:1500]}
"""
    body = {"model": MODEL, "prompt": prompt, "stream": False,
            "format": "json", "options": {"temperature": 0.1}}
    req = urllib.request.Request(OLLAMA + "/api/generate",
                                 data=json.dumps(body, ensure_ascii=False).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=600, context=ssl._create_unverified_context()) as r:
        raw = json.load(r).get("response", "")
    m = re.search(r"\{.*\}", raw, re.S)
    if not m:
        return 0.0, "裁判未返回 JSON", ["judge_invalid_json"]
    try:
        d = json.loads(m.group(0))
        return max(0.0, min(1.0, float(d.get("score", 0)))), str(d.get("reason", ""))[:300], d.get("issues") or []
    except Exception as e:
        return 0.0, f"裁判解析失败：{e}", ["judge_parse_error"]


data = json.load(open(SRC, encoding="utf-8"))
rows = [r for r in data["results"] if not r.get("passed")] if ONLY_FAILED else data["results"]
rows = rows[:LIMIT]
out = []
for i, r in enumerate(rows, 1):
    t = time.time()
    score, reason, issues = judge(r["question"], r.get("expectedAnswer", ""),
                                  r.get("scoreCriteria", ""), r.get("answer", ""),
                                  r.get("citations", []))
    out.append({**r, "crossJudgeModel": MODEL, "crossScore": round(score, 4),
                "crossPassed": score >= 0.75, "crossReason": reason,
                "crossIssues": issues, "crossLatencySeconds": round(time.time() - t, 2)})
    print(f"[{i}/{len(rows)}] {score:.2f} {r['question'][:35]} {round(time.time()-t,1)}s", flush=True)

summary = {
    "source": SRC, "model": MODEL, "onlyFailed": ONLY_FAILED,
    "total": len(out), "avgCrossScore": round(sum(x["crossScore"] for x in out) / len(out), 4) if out else None,
    "crossPassRate": round(sum(1 for x in out if x["crossPassed"]) / len(out), 4) if out else None,
    "results": out,
}
json.dump(summary, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(json.dumps({k: summary[k] for k in ("total", "avgCrossScore", "crossPassRate", "model")},
                 ensure_ascii=False), flush=True)
print("OUT", OUT, flush=True)
