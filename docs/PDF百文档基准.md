# PDF 百文档解析基准（2026-10-31）

## 语料

从公开 arXiv 取金融与化学物理/物理分类 PDF，固定 100 篇不同文档：

- `q-fin`：76 篇
- `physics.chem-ph`：24 篇

下载脚本只使用 Python 标准库，写入 `manifest.json`，样本不进入仓库：

```bash
python3 scripts/download_pdf_corpus.py \
  --limit 100 \
  --out backend/target/pdf-corpus
```

## 运行

```bash
cd backend
PDF_BENCH_DIR="$PWD/target/pdf-corpus" \
  mvn -Dtest=PdfBenchmarkTest test
cat target/pdf-bench-report.md
```

## 本机结果

| 指标 | 结果 |
| --- | ---: |
| 文档数 | 100 |
| 总页数 | 3,077 |
| 总字符 | 10,164,414 |
| 分段数 | 25,475 |
| 标题数 | 2,775 |
| 表格分段数 | 403 |
| 原始表块数 | 274 |
| 总耗时 | 30.0s |
| 平均耗时 | 300ms/篇 |
| 失败 | 0 |

全部 100 篇状态均为 `OK`，页码覆盖完整（允许末尾空白页不产生分段）。

## CI

`.github/workflows/pdf-corpus-nightly.yml` 每周或手动下载 100 篇语料并执行
`PdfBenchmarkTest`，上传 `pdf-bench-report.md` 和 `manifest.json`。首次运行需要下载约
176MB 样本。

## 说明

- 这套语料用于发现“标题/表格整体消失、正文为空、页面大段丢失”这类回归；
- 它不替代人工标注的表格结构基准，合并单元格、多行表头的正确率仍需单独样本；
- 语料按 arXiv 分类和提交时间选取，arXiv 更新后 ID 集合可能变化；发布报告时应保存 `manifest.json`。
