#!/usr/bin/env bash
# 下载公开的 PDF 解析回归样本（金融 + 物理化学/标准文档）。
# 样本不进入仓库；默认落到 backend/target/pdf-samples。
set -euo pipefail

OUT="${1:-backend/target/pdf-samples}"
UA="ai-agent-platform-pdf-benchmark/1.0 (${PDF_BENCH_CONTACT:-platform@example.com})"
mkdir -p "$OUT"

download() {
  local name="$1" url="$2"
  echo "GET $name"
  curl -fL --retry 2 --connect-timeout 20 --max-time 300 \
    -H "User-Agent: $UA" \
    -o "$OUT/$name" "$url"
}

# 金融：Berkshire 年报、Apple 10-K、JPMorgan 年报（先下载完整版）
download "2023ar.pdf" "https://www.berkshirehathaway.com/2023ar/2023ar.pdf"
download "apple-10k-2024.pdf" "https://s2.q4cdn.com/470004039/files/doc_earnings/2024/q4/filing/10-Q4-2024-As-Filed.pdf"
download "jpm-2024-full.pdf" "https://www.sec.gov/Archives/edgar/data/19617/000001961725000329/annualreport-2024.pdf"

# 物理化学 / 数学 / 专业标准
download "chem-2012.13202.pdf" "https://arxiv.org/pdf/2012.13202"
download "chem-2610.40338.pdf" "https://arxiv.org/pdf/2610.40338"
download "chem-2610.40621.pdf" "https://arxiv.org/pdf/2610.40621"
download "math-2012.13167.pdf" "https://arxiv.org/pdf/2012.13167"
download "nist-sp800-53r5.pdf" "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-53r5.pdf"

# JPM 完整年报约 34MB，超过平台 20MB 上传上限；截取前 120 页做解析压测。
if python3 -c 'import pymupdf' >/dev/null 2>&1; then
  python3 - "$OUT/jpm-2024-full.pdf" "$OUT/jpm-2024-first120.pdf" <<'PY'
import sys
import pymupdf
src, dst = sys.argv[1], sys.argv[2]
doc = pymupdf.open(src)
doc.select(range(min(120, doc.page_count)))
doc.save(dst, garbage=4, deflate=True, clean=True)
doc.close()
PY
  rm -f "$OUT/jpm-2024-full.pdf"
else
  echo "WARN: 未安装 pymupdf，保留完整 JPM 年报；它超过 20MB，解析前需自行截页" >&2
fi

echo "样本目录：$OUT"
ls -lh "$OUT"
