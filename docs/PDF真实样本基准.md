# PDF 真实样本基准验证（2026-10-25）

## 样本来源

从公开的医学、化学、数学、生物、计算机、金融和专业标准站点下载，覆盖单栏/双栏、
正文/图表/复杂金融表格、超长文档等类型：

| 样本 | 来源 | 类型 | 页数 |
| --- | --- | --- | ---: |
| bio-plos.pdf | PLOS ONE, 10.1371/journal.pone.0000217 | 生物、双栏 | 8 |
| chem-arxiv.pdf | arXiv 2012.13202（physics.chem-ph） | 化学物理、双栏 | 9 |
| chem-rsc.pdf | RSC Advances（PMC13644730） | 化学综述、双栏 | 23 |
| cs-attention.pdf | arXiv 1706.03762 | 计算机、双栏 | 15 |
| finance-brka.pdf | Berkshire Hathaway 2023 Annual Report | 金融年报、复杂表格 | 152 |
| math-arxiv.pdf | arXiv 2012.13167（math.AG） | 数学、单栏 | 50 |
| med-pain.pdf | Paediatric & Neonatal Pain（PMC13615402） | 医学综述、附录大表 | 25 |
| med-review.pdf | Rev Assoc Med Bras（PMC13693301） | 医学系统综述、PRISMA 图 | 7 |
| med-scanned-1960.pdf | Br J Cancer 1960（PMC2074205） | 医学扫描件、OCR | 9 |
| nist-sp800-53.pdf | NIST SP 800-53 Rev.5 | 专业标准、超长文档 | 492 |

可复现来源：

- PLOS ONE: `https://journals.plos.org/plosone/article/file?id=10.1371/journal.pone.0000217&type=printable`
- arXiv: `https://arxiv.org/pdf/2012.13202`、`https://arxiv.org/pdf/2012.13167`、`https://arxiv.org/pdf/1706.03762`
- RSC Advances: `https://pmc.ncbi.nlm.nih.gov/articles/PMC13644730/pdf/RA-016-D6RA08128B.pdf`
- 医学样本：`https://pmc.ncbi.nlm.nih.gov/articles/PMC13615402/pdf/PNE2-8-e70048.pdf`、
  `https://pmc.ncbi.nlm.nih.gov/articles/PMC13693301/pdf/1806-9282-ramb-72-10-e20260669.pdf`
- 1960 年医学扫描件：`https://pmc.ncbi.nlm.nih.gov/articles/PMC2074205/pdf/brjcancer00365-0123.pdf`
- NIST: `https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-53r5.pdf`
- Berkshire Hathaway: `https://www.berkshirehathaway.com/2023ar/2023ar.pdf`

> PMC 下载页会做浏览器 proof-of-work 校验；脚本化下载时需要先解 challenge、写入
> `cloudpmc-viewer-pow` Cookie，再请求 PDF。

## 复现方式

```bash
cd backend
PDF_BENCH_DIR=/path/to/pdf-samples mvn -Dtest=PdfBenchmarkTest test
cat target/pdf-bench-report.md
```

默认使用自包含 fixture 运行；设置 `PDF_BENCH_DIR` 时改跑真实语料。报告包含每份 PDF 的页数、
耗时、分段数、字符数、标题数、表格数、图片数、页码覆盖和正文预览。

## 结果（修复后）

| 文件 | 耗时 | 分段 | 标题 | 表格 | 页码覆盖 | 状态 |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| bio-plos.pdf | 346ms | 134 | 2 | 0 | 1-8 | OK |
| chem-arxiv.pdf | 240ms | 101 | 0 | 0 | 1-9 | OK |
| chem-rsc.pdf | 339ms | 325 | 7 | 0 | 1-23 | OK |
| cs-attention.pdf | 318ms | 109 | 12 | 5 | 1-15 | OK |
| finance-brka.pdf | 1313ms | 1706 | 7 | 3 | 1-152 | OK |
| math-arxiv.pdf | 341ms | 298 | 9 | 0 | 1-50 | OK |
| med-pain.pdf | 374ms | 764 | 11 | 10 | 1-25 | OK |
| med-review.pdf | 99ms | 79 | 16 | 9 | 1-7 | OK |
| med-scanned-1960.pdf | 49ms | 55 | 34 | 1 | 1-9 | OK |
| nist-sp800-53.pdf | 2491ms | 4762 | 184 | 5 | 1-492 | OK |

全部样本解析成功，无异常；492 页 NIST 标准约 2.5s，152 页年报约 1.3s（本机）。

> 表格数按解析出的表格块计；超大 Markdown 表格会按行拆成多块并重复表头，
> 因此“表格数”可能大于原始表格数量。

## 扫描件 OCR 验证

从 PMC 下载 1960 年《British Journal of Cancer》组织化学论文（PMC2074205），
原 PDF 带有 OCR 文本层；为了验证纯图片扫描链路，测试会：

1. 用 PDFBox 把前 3 页渲染成图片；
2. 重新生成**无文本层**的图片型 PDF；
3. 以 `ParseOptions(true, tesseract, eng, 150 DPI, 3 页)` 走按页 OCR。

结果：

```text
pages=1-3 chunks=30 chars=17519 ms=2727
```

OCR 能识别标题、作者、摘要和正文（`HISTOCHEMICAL`、`CARCINOGENESIS`、`RAT LIVER` 等），
页码覆盖 1–3。OCR 文本存在少量断词和 50 字重叠（分段本身的设计），不影响检索。

复现：

```bash
PDF_BENCH_DIR=/path/to/pdf-samples mvn -Dtest=PdfScannedBenchmarkTest test
```

## 本轮发现并修复的问题

### 1. 学术 PDF 首部被误判为整页表格

**现象**：PLOS、arXiv、RSC 的双栏论文开头出现 20–400 列的巨型 HTML 表格，
内容是作者、单位、邮箱、页眉/页脚和正文片段；表格占据大量分段和 token。

**原因**：`PdfTableExtractor` 的文本坐标启发式只看“至少 3 行、最多列数至少 3、
列锚点对齐”，作者块/双栏正文/页眉页脚也满足条件。

**修复**：

- 增加表格质量门槛：
  - 有 `Table/表` 标题的表格保留，但限制空单元格比例、列数、总单元格数；
  - 无标题表格要求列数 ≤12、空单元格比例 ≤40%、rowspan ≤20、无多个邮箱、
    高度不超过页面 78%；
- 表格提取只使用质量合格的候选，避免把整页版面当表格。

### 2. 标题严重过检

**现象**：152 页年报识别出 1295 个标题，NIST 标准识别出 5949 个标题，大量正文段落
被输出成 `###`。

**原因**：全局正文字号用“中位数”计算，长文档里小额表格/脚注把中位数拉低，
正文相对“变大”后被误判为标题。

**修复**：

- 正文字号改为**按页字符数加权众数**，每页独立判断；
- 标题长度上限降到 80 字符；
- 数字占比超过 30% 的短行不作为标题（过滤财务表头如 “2021 2023 vs 2022…”）；
- 连续同级别、同字号、间距接近的标题行合并（修复标题跨行被拆成多个 H1）。

修复后：年报标题 1295 → 7，NIST 5949 → 249。

### 3. 表格质量仍然参差

- `med-pain` 的 TABLE A1 有 67 列且大量空单元格，已按质量门槛丢弃；
  表格文字仍保留在正文流中，不会丢内容。
- TABLE A2–A4 等附录大表保留为 HTML，但部分单元格因多行文本被拆成多行，
  `rowspan` 推断偏激进；后续需要针对附录表做单元格合并优化。
- `med-review` 的 PRISMA 流程图被识别为 HTML 表格；内容可用，但不是严格数据表。
- NIST 的签名/职责名单被识别为小表格；影响较小。

## 仍未解决 / 后续

- **扫描版专业 PDF 未纳入本轮样本**（本轮 9 份均有文本层）；需要补医学扫描件、
  化学扫描图谱、带表格的扫描合同。
- 数学论文的章节标题多为同字号加粗或行内标题，当前识别仍偏保守（math-arxiv 9 个标题，
  主要是论文标题和部分章节）；正文仍在，但标题层级可继续改进。
- 金融年报中部分无标题、列数超过 12 的真实表格会被丢弃（正文文字保留）；
  后续应改为“按版面区域切分 + 表格密度判断”，而不是整页判断。
- 复杂合并单元格/多行表头的单元格切分仍需真实样本迭代。
- 大表格按行拆分目前只对 Markdown 管道表生效；含合并单元格的 HTML 表格保持原子，
  超大 HTML 表格可能超过单块 embedding 长度。
