#!/usr/bin/env python3
"""下载至少 N 篇公开 arXiv PDF，作为 PDF 解析百文档回归语料。

只使用 Python 标准库。默认从化学物理、金融、数学/统计分类取候选，
按 arXiv API 返回顺序去重后下载，并写入 manifest.json。

  python3 scripts/download_pdf_corpus.py --limit 100

输出目录默认 backend/target/pdf-corpus，不进入仓库。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ATOM = "{http://www.w3.org/2005/Atom}"
CATEGORIES = [
    ("chem", "physics.chem-ph"),
    ("finance", "q-fin.GN"),
    ("finance", "q-fin.PM"),
    ("finance", "q-fin.RM"),
    ("finance", "q-fin.TR"),
    ("math", "math.PR"),
    ("math", "math.ST"),
]
UA = "ai-agent-platform-pdf-corpus/1.0 (platform@example.com)"


def fetch(url: str, timeout: int = 60) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def candidates(category: str, per_category: int) -> list[dict]:
    url = ("https://export.arxiv.org/api/query?search_query=cat:"
           + urllib.parse.quote(category)
           + "&start=0&max_results=" + str(per_category)
           + "&sortBy=submittedDate&sortOrder=descending")
    body = fetch(url)
    root = ET.fromstring(body)
    result = []
    for entry in root.findall(ATOM + "entry"):
        id_url = entry.findtext(ATOM + "id", default="")
        title = " ".join((entry.findtext(ATOM + "title", default="") or "").split())
        match = re.search(r"/abs/([^/]+)$", id_url)
        if match:
            result.append({"category": category.split(".")[0], "id": match.group(1), "title": title})
    return result


def download(item: dict, out: Path) -> dict | None:
    arxiv_id = item["id"]
    path = out / f"{item['category']}-{arxiv_id}.pdf"
    if path.exists() and path.read_bytes()[:4] == b"%PDF":
        return {**item, "file": path.name}
    url = f"https://arxiv.org/pdf/{arxiv_id}"
    for attempt in range(3):
        try:
            data = fetch(url)
            if data[:4] != b"%PDF":
                raise RuntimeError("not a PDF")
            tmp = path.with_suffix(".part")
            tmp.write_bytes(data)
            tmp.replace(path)
            return {**item, "file": path.name}
        except Exception as error:
            if attempt == 2:
                print(f"WARN skip {arxiv_id}: {error}", file=sys.stderr)
                return None
            time.sleep(1 + attempt)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--per-category", type=int, default=30)
    parser.add_argument("--out", default="backend/target/pdf-corpus")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    if args.limit < 100:
        parser.error("PDF 回归语料至少需要 100 篇；请使用 --limit >= 100")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    pool: list[dict] = []
    for _name, category in CATEGORIES:
        try:
            part = candidates(category, args.per_category)
            print(f"candidate {category}: {len(part)}")
            for item in part:
                if item["id"] not in seen:
                    seen.add(item["id"])
                    pool.append(item)
        except Exception as error:
            print(f"WARN category {category}: {error}", file=sys.stderr)
    if len(pool) < args.limit:
        print(f"候选不足：需要 {args.limit}，只有 {len(pool)}", file=sys.stderr)
        return 2

    selected = pool[:args.limit]
    manifest: list[dict] = []
    with ThreadPoolExecutor(max_workers=max(1, min(8, args.workers))) as executor:
        futures = {executor.submit(download, item, out): item for item in selected}
        for index, future in enumerate(as_completed(futures), 1):
            result = future.result()
            if result:
                manifest.append(result)
            print(f"download {index}/{len(selected)} -> {len(manifest)}", flush=True)

    if len(manifest) < 100:
        print(f"下载成功不足 100 篇：{len(manifest)}", file=sys.stderr)
        return 3
    # 按稳定顺序写 manifest，便于复现和比较。
    manifest.sort(key=lambda item: item["id"])
    (out / "manifest.json").write_text(
        json.dumps({"count": len(manifest), "documents": manifest},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OK {len(manifest)} PDFs -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
