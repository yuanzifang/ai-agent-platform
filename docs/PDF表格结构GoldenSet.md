# PDF 表格结构 Golden Set（第一阶段）

## 目标

不只看“解析出了几个表格”，而是检查表格结构是否保留：

- 是否存在表头；
- 最大列数；
- 单元格总量；
- 非空/空单元格数量；
- rowspan/colspan 数量；
- Markdown 管道表与 HTML 表格统一评估。

实现：`backend/src/test/java/com/platform/knowledge/service/TableStructureMetrics.java`。

## 当前固定案例

1. `PdfSnapshotTest` 的合成三页 PDF 表格：
   - 至少 1 个表格；
   - 至少 1 行表头；
   - 至少 3 列；
   - 至少 9 个非空单元格。
2. `PdfTableLayoutQualityTest` 的双行表头：
   - 两行标签都进入 `<thead>`；
   - 至少 5 列；
   - 至少 7 个非空单元格。
3. 目录/签名/缺表头/空单元格比例通过既有质量告警测试覆盖。

## 限制

- 当前主要是合成 PDF/HTML，尚未包含人工标注的真实扫描表格；
- 指标只能证明结构没有明显丢失，不能证明每个合并单元格都正确；
- 下一阶段需要把真实金融表、NBS 扫描表、医学/合同扫描件加入 fixture，并计算单元格级 precision/recall/F1。

## 运行

```bash
cd backend
mvn -q -Dtest=PdfSnapshotTest,PdfTableLayoutQualityTest test
```
