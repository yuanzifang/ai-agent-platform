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

> 基准报告里的“表格”按解析后的表格分段计数；超大表格会按行拆成多块并重复表头，
> 因此该数字可能明显大于原始物理表格数。原始表块数见本轮复测表。

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

## 本轮安全加固（2026-10-30）

- PDF 整页渲染统一经过像素上限：默认 1600 万像素，A0/A1 页面自动降 DPI；单页扫描件的内嵌图也会做兜底缩放。
- `DocumentParser` 显式设置 POI `ZipSecureFile`：单 entry 64MB、最小解压比 1%，防 DOCX/XLSX 解压炸弹。
- 超大代码块按行切分并保留 fence；超大表格优先按行拆且重复表头，无法安全拆分的 HTML 表格由 embedding 层显式报错。
- 新增 `PdfSnapshotTest`（合成三页 PDF，校验标题数/表格数/页码覆盖），并接入 CI。
- 详细配置见 `docs/上下文与解析安全.md`。


## 2026-10-30 金融与物理化学公开样本复测

从公开站点下载了金融年报、SEC 10-K、银行年报和物理化学预印本，使用
`PDF_BENCH_DIR` 复跑。PDF 不进入仓库，可用脚本重新下载：

```bash
bash scripts/download_pdf_samples.sh backend/target/pdf-samples
cd backend
PDF_BENCH_DIR="$PWD/target/pdf-samples" mvn -Dtest=PdfBenchmarkTest test
```

真实样本基准和扫描件 OCR 基准已接入 nightly GitHub Actions（`.github/workflows/pdf-nightly.yml`）：
定时或手动下载公开金融/物化/扫描样本，解析失败、正文为空、页码覆盖不足，或标题/表格数量
低于按样本设定阈值时会直接失败，并上传 `pdf-bench-report.md`。PR 仍使用无需外部下载的合成 `PdfSnapshotTest`。

| 样本 | 类型 | 页数 | 分段 | 标题 | 表格块 | 页码覆盖 | 耗时 | 状态 |
| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 2023ar.pdf | Berkshire 2023 年报 | 152 | 1825 | 6 | 72 | 1-152 | 2.9s | OK |
| apple-10k-2024.pdf | Apple FY2024 10-K | 121 | 1260 | 32 | 12 | 1-121 | 1.0s | OK |
| jpm-2024-first120.pdf | JPMorgan 2024 年报前 120 页 | 120 | 1245 | 56 | 4 | 1-120 | 1.4s | OK |
| chem-2012.13202.pdf | 物理化学（NOF-MP2） | 9 | 101 | 1 | 0 | 1-9 | 0.16s | OK |
| chem-2610.40338.pdf | 电化学机器学习 | 15 | 179 | 23 | 1 | 1-15 | 0.35s | OK |
| chem-2610.40621.pdf | 密度泛函理论 | 25 | 185 | 28 | 0 | 1-25 | 0.16s | OK |
| math-2012.13167.pdf | 数学（DT4） | 50 | 298 | 9 | 0 | 1-50 | 0.67s | OK |
| nist-sp800-53r5.pdf | NIST SP 800-53 r5 | 492 | 4713 | 176 | 21 | 1-492 | 5.7s | OK |

### 由此发现并修复的问题

1. **表格单元格粘连**：PDF 没有显式空格时，`TableofContents`、`Fairvaluemeasurements...`
   这类文本会直接拼在一起。已在文本坐标、矩形表格、线条表格三条路径按字形间距补空格。
2. **目录/封面/签名页被识别成表格**：Berkshire 目录、Apple 10-K 封面和目录、签名页
   会产生大量空表。新增统一表格质量门槛：过滤目录 caption、SEC 封面、签名块、长段正文框和
   空白率过高的伪表。
3. **物理化学首页长标题未识别**：标题超过 80 字符时被标题长度上限丢弃；首页顶部增加
   长标题兜底，`2012.13202` 从 0 个标题变为 1 个。
4. **重复表格块**：同一页重叠且内容高度相似的表块去重，避免文本抽取器重复捕获。
5. **年份/Change 表头**：Apple 10-K 第 25–26 页的“2024 / Change / 2023 / Change”表头
   过去不进入 `<thead>`，现在能识别为表头；数据行不会混入表头。

> 表格块数是 `PdfTableLayout` 输出的原始表块数；“表格”分段数还会因 1500 字符切分和重复表头而变大，
> 所以基准报告中 BRK 的表格数会明显高于表格块数。

### 扫描版专业样本（2026-10-31 追加）

| 样本 | 页数 | 类型 | 处理方式 | 结果 |
| --- | ---: | --- | --- | --- |
| scanned-nbs-chem-1972.pdf | 122 | 1972 年 NBS 电解质数据中心报告 | 自带部分 OCR 文本层，按页补 OCR | 1.1s；76,464 字符；正文可读，老式打字/公式噪声明显 |
| scanned-nbs-catalog-table-pages.pdf | 3 | 1986 年 NBS 标准物质目录表格页 | 从目录 PDF 渲染为无文本层图片 PDF，150 DPI OCR | 2.7s；4,202 字符；标题和表格文字可识别，已能重建 HTML 表块 |
| scanned-epa-letter-2015.pdf | 3 | 2015 年 EPA 饮用水政策扫描函件 | 无文本层，150 DPI OCR | 2.3s；10,952 字符；标题、正文和 3 个表块可读 |

扫描表格页能识别出 `Copper-Base Alloys (Solid Form)`、元素列和数值。新增按行间距/列数
逐级尝试、以最宽行作为列锚点的表块重建后，样例可生成含表头的 8 列左右 HTML 表；
但 OCR/复杂版面噪声仍会造成空单元格、列错位和合并单元格偏差；解析产物中空单元格比例过高的表格
会写入人工复核告警，不能把扫描表格当成与文本层 PDF 同等级的结构化结果。

复现：

```bash
# 下载 NBS 报告和目录
bash scripts/download_pdf_samples.sh backend/target/pdf-samples
# 用 PyMuPDF 选取目录表格页生成无文本层图片 PDF（示例页 39-41）
python3 - <<'PY'
import pymupdf
src = pymupdf.open("backend/target/pdf-samples/scanned-nbs-catalog-1986.pdf")
out = pymupdf.open()
for i in [38, 39, 40]:
    page = src[i]
    pix = page.get_pixmap(dpi=200, colorspace=pymupdf.csRGB, alpha=False)
    p = out.new_page(width=page.rect.width, height=page.rect.height)
    p.insert_image(p.rect, stream=pix.tobytes("png"))
out.save("backend/target/pdf-samples/scanned-nbs-catalog-table-pages.pdf", garbage=4, deflate=True)
PY
```

### 仍未解决

- 首页标题跨行时，第二行和作者行仍可能被拆成普通段落（`2012.13202` 的标题后半段）。
- 金融宽表的合并单元格、跨页表头仍需真实样本继续迭代；当前优先保证表格文字不丢。
- JPMorgan 完整年报 34MB，超过平台 20MB 上限；脚本默认截取前 120 页做压测。
- RSC 的 PMC PDF 需要浏览器 proof-of-work，脚本未纳入；物理化学样本改用 arXiv 开放 PDF。

## 仍未解决 / 后续

- **扫描版专业 PDF 未纳入本轮样本**（本轮 9 份均有文本层）；需要补医学扫描件、
  化学扫描图谱、带表格的扫描合同。
- 数学论文的章节标题多为同字号加粗或行内标题，当前识别仍偏保守（math-arxiv 9 个标题，
  主要是论文标题和部分章节）；正文仍在，但标题层级可继续改进。
- 金融年报中部分无标题、列数超过 12 的真实表格会被丢弃（正文文字保留）；
  后续应改为“按版面区域切分 + 表格密度判断”，而不是整页判断。
- 复杂合并单元格/多行表头的单元格切分仍需真实样本迭代。
- 大表格按行拆分目前对 Markdown 管道表和多数 HTML 表生效；含合并单元格/多行表头的
  HTML 表格仍保持原子。超过 `embedding.max-input-tokens` 时会明确报错，不再静默截断，
  但还需要针对真实合并单元格样本做安全切分。
