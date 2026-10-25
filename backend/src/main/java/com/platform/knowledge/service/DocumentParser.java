package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xddf.usermodel.chart.XDDFChart;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档解析与分段（架构 6.1/6.2/6.3）：MD/TXT/CSV/JSON、DOCX/XLSX/XLS/PPTX(POI)、PDF(PDFBox)、HTML(Jsoup)、图片；
 * 父子分块：父块约 2000 字符、子块 500 字符/50 重叠，子块保存 parent_content 供检索注入。
 * 扫描版 PDF/图片可选 OCR（外部 tesseract 命令，knowledge.ocr.enabled=true）。
 */
@Slf4j
@Service
public class DocumentParser {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int PARENT_SIZE = 2000;
    private static final long MAX_BYTES = 20 * 1024 * 1024;

    @Value("${knowledge.ocr.enabled:false}")
    private boolean ocrEnabled;
    @Value("${knowledge.ocr.command:tesseract}")
    private String ocrCommand;
    @Value("${knowledge.ocr.languages:chi_sim+eng}")
    private String ocrLanguages;
    @Value("${knowledge.ocr.max-pages:20}")
    private int ocrMaxPages;
    @Value("${knowledge.ocr.dpi:150}")
    private int ocrDpi;
    @Value("${knowledge.ocr.preprocess:true}")
    private boolean ocrPreprocess;
    @Value("${knowledge.ocr.auto-orient:true}")
    private boolean ocrAutoOrient;
    @Value("${knowledge.pdf.vector-chart-render-enabled:true}")
    private boolean vectorChartRenderEnabled;

    public record ParsedChunk(String content, int parentIndex, String parentContent) {
    }

    public record ExtractedImage(byte[] data, String extension) {
    }

    /** 提取文档内嵌图片原文件（不依赖 OCR 开关）；失败时返回空列表，不阻断正文解析。 */
    public List<ExtractedImage> extractImages(String fileName, byte[] bytes) {
        List<ExtractedImage> images = new ArrayList<>();
        if (bytes == null || bytes.length == 0) return images;
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    for (XWPFPictureData picture : doc.getAllPictures()) {
                        addImage(images, picture.getData(), picture.suggestFileExtension());
                    }
                }
            } else if (lower.endsWith(".pptx")) {
                try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
                    for (XSLFSlide slide : ppt.getSlides()) {
                        for (XSLFShape shape : slide.getShapes()) {
                            if (shape instanceof XSLFPictureShape picture) {
                                addImage(images, picture.getPictureData().getData(),
                                        picture.getPictureData().suggestFileExtension());
                            }
                        }
                    }
                }
            } else if (lower.endsWith(".pdf")) {
                try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                    PDFRenderer renderer = new PDFRenderer(doc);
                    int count = 0;
                    for (int p = 0; p < doc.getNumberOfPages() && count < 20; p++) {
                        PDPage page = doc.getPage(p);
                        PDResources resources = page.getResources();
                        if (resources != null) {
                            for (var name : resources.getXObjectNames()) {
                                if (count >= 20) break;
                                PDXObject x = resources.getXObject(name);
                                if (!(x instanceof PDImageXObject image)) continue;
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                ImageIO.write(image.getImage(), "png", out);
                                addImage(images, out.toByteArray(), "png");
                                count++;
                            }
                        }
                        // 矢量图表可能和照片/位图同页；独立检测并渲染整页，避免组合页漏图表。
                        if (count < 20 && vectorChartRenderEnabled && hasVectorDrawing(page, 6)) {
                            BufferedImage rendered = renderer.renderImageWithDPI(p, 150);
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            ImageIO.write(rendered, "png", out);
                            addImage(images, out.toByteArray(), "png");
                            count++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("提取文档图片失败（忽略）：{}", e.getMessage());
        }
        return images;
    }

    private boolean hasVectorDrawing(PDPage page, int minPaintOps) {
        PDFStreamParser parser = null;
        try {
            parser = new PDFStreamParser(page);
            int paints = 0;
            for (Object token : parser.parse()) {
                if (!(token instanceof Operator op)) continue;
                String name = op.getName();
                if ("S".equals(name) || "s".equals(name) || "f".equals(name) || "F".equals(name)
                        || "f*".equals(name) || "B".equals(name) || "B*".equals(name)
                        || "b".equals(name) || "b*".equals(name)) {
                    paints++;
                }
            }
            return paints >= minPaintOps;
        } catch (Exception e) {
            return false;
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addImage(List<ExtractedImage> images, byte[] data, String extension) {
        if (data == null || data.length == 0 || data.length > 10 * 1024 * 1024 || images.size() >= 20) return;
        images.add(new ExtractedImage(data, extension));
    }

    public List<String> parse(String fileName, byte[] bytes) {
        return parseWithParents(fileName, bytes).stream().map(ParsedChunk::content).toList();
    }

    public List<ParsedChunk> parseWithParents(String fileName, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new BusinessException(400, "文件超过 20MB 限制");
        String lower = fileName.toLowerCase(Locale.ROOT);
        String text;
        try {
            if (lower.endsWith(".docx")) text = parseDocx(bytes);
            else if (lower.endsWith(".doc")) text = parseDoc(bytes);
            else if (lower.endsWith(".ppt")) text = parsePpt(bytes);
            else if (lower.endsWith(".pdf")) {
                text = parsePdf(bytes);
                if (needsOcr(text)) text = parsePdfOcr(bytes, fileName);
            } else if (lower.endsWith(".xlsx")) text = parseExcel(bytes, false);
            else if (lower.endsWith(".xls")) text = parseExcel(bytes, true);
            else if (lower.endsWith(".pptx")) text = parsePptx(bytes);
            else if (lower.endsWith(".epub")) text = parseEpub(bytes);
            else if (lower.endsWith(".html") || lower.endsWith(".htm")) text = Jsoup.parse(
                    new String(bytes, StandardCharsets.UTF_8)).text();
            else if (isImage(lower)) text = ocrImage(bytes, lower);
            else if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
                    || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".log"))
                text = new String(bytes, StandardCharsets.UTF_8);
            else throw new BusinessException(400,
                    "不支持的格式（支持 PDF/DOC/DOCX/XLSX/XLS/PPT/PPTX/EPUB/HTML/TXT/MD/CSV/JSON/PNG/JPG）：" + fileName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(422, "文档解析失败：" + e.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException(422, ocrEnabled ? "文档内容为空（OCR 未识别到文本）"
                    : "文档内容为空；扫描版 PDF/图片请启用 knowledge.ocr.enabled");
        }
        return splitWithParents(text);
    }

    private String parseDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                sb.append(parseDocxTable(table));
            }
            if (ocrEnabled) {
                for (XWPFPictureData picture : doc.getAllPictures()) {
                    appendOcr(sb, picture.getData(), picture.suggestFileExtension());
                }
            }
        }
        return sb.toString();
    }

    /** DOCX 表格转 HTML，保留 gridSpan(colspan) 与 vMerge(rowspan)。 */
    private String parseDocxTable(XWPFTable table) {
        StringBuilder sb = new StringBuilder("<table border=\"1\">\n");
        List<XWPFTableRow> rows = table.getRows();
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<tr>");
            int col = 0;
            for (XWPFTableCell cell : rows.get(r).getTableCells()) {
                var pr = cell.getCTTc().getTcPr();
                int colspan = 1;
                if (pr != null && pr.getGridSpan() != null && pr.getGridSpan().getVal() != null) {
                    colspan = Math.max(1, pr.getGridSpan().getVal().intValue());
                }
                String merge = pr != null && pr.getVMerge() != null
                        ? String.valueOf(pr.getVMerge().getVal()) : "";
                if ("continue".equalsIgnoreCase(merge)) {
                    col += colspan;
                    continue;
                }
                int rowspan = 1;
                if ("restart".equalsIgnoreCase(merge)) {
                    for (int rr = r + 1; rr < rows.size(); rr++) {
                        if (col >= rows.get(rr).getTableCells().size()) break;
                        var nextPr = rows.get(rr).getTableCells().get(col).getCTTc().getTcPr();
                        String nextMerge = nextPr != null && nextPr.getVMerge() != null
                                ? String.valueOf(nextPr.getVMerge().getVal()) : "";
                        if (!"continue".equalsIgnoreCase(nextMerge)) break;
                        rowspan++;
                    }
                }
                String text = cell.getText() == null ? "" : cell.getText().trim();
                sb.append("<td");
                if (colspan > 1) sb.append(" colspan=\"").append(colspan).append("\"");
                if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append("\"");
                sb.append(">").append(escapeHtml(text)).append("</td>");
                col += colspan;
            }
            sb.append("</tr>\n");
        }
        return sb.append("</table>\n").toString();
    }

    private String parseDoc(byte[] bytes) throws Exception {
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
            return extractor.getText();
        }
    }

    private String parsePpt(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (HSLFSlideShow ppt = new HSLFSlideShow(new ByteArrayInputStream(bytes))) {
            for (HSLFSlide slide : ppt.getSlides()) {
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape text) {
                        String value = text.getText();
                        if (value != null && !value.isBlank()) sb.append(value).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            StringBuilder sb = new StringBuilder(new PDFTextStripper().getText(doc));
            String tables = PdfTableExtractor.extract(doc);
            if (!tables.isBlank()) sb.append("\n").append(tables).append('\n');
            if (ocrEnabled) {
                int count = 0;
                for (int p = 0; p < doc.getNumberOfPages() && count < 20; p++) {
                    PDResources resources = doc.getPage(p).getResources();
                    if (resources == null) continue;
                    for (var name : resources.getXObjectNames()) {
                        if (count >= 20) break;
                        PDXObject x = resources.getXObject(name);
                        if (!(x instanceof PDImageXObject image)) continue;
                        appendOcr(sb, image.getImage(), "png");
                        count++;
                    }
                }
            }
            return sb.toString();
        }
    }

    private String parseExcel(byte[] bytes, boolean xls) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = xls ? new HSSFWorkbook(new ByteArrayInputStream(bytes))
                : new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                sb.append("## ").append(sheet.getSheetName()).append('\n');
                Map<String, CellRangeAddress> starts = new LinkedHashMap<>();
                for (CellRangeAddress range : sheet.getMergedRegions()) {
                    starts.put(range.getFirstRow() + ":" + range.getFirstColumn(), range);
                }
                int lastRow = Math.min(sheet.getLastRowNum(), 999);
                sb.append("<table border=\"1\">\n");
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    sb.append("<tr>");
                    int lastCol = Math.min(Math.max(row.getLastCellNum(), 0), 100);
                    for (int c = 0; c < lastCol; c++) {
                        CellRangeAddress merged = starts.get(r + ":" + c);
                        if (merged == null && inMerged(sheet, r, c)) continue;
                        int colspan = 1;
                        int rowspan = 1;
                        if (merged != null) {
                            colspan = merged.getLastColumn() - merged.getFirstColumn() + 1;
                            rowspan = merged.getLastRow() - merged.getFirstRow() + 1;
                        }
                        Cell cell = row.getCell(c);
                        String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                        if (value.isEmpty() && merged == null) continue;
                        sb.append("<td");
                        if (colspan > 1) sb.append(" colspan=\"").append(colspan).append("\"");
                        if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append("\"");
                        sb.append(">").append(escapeHtml(value)).append("</td>");
                    }
                    sb.append("</tr>\n");
                }
                sb.append("</table>\n");
                if (sheet instanceof XSSFSheet xssfSheet) {
                    XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
                    if (drawing != null) {
                        for (XSSFChart chart : drawing.getCharts()) {
                            String title = chart.getTitleText() == null ? null : chart.getTitleText().getString();
                            appendChart(sb, chart, title);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private void appendChart(StringBuilder sb, XDDFChart chart, String title) {
        if (chart == null) return;
        StringBuilder out = new StringBuilder();
        if (title != null && !title.isBlank()) out.append("图表标题：").append(title.trim()).append("\n");
        int seriesGroups = 0, totalPoints = 0;
        try {
            for (XDDFChartData data : chart.getChartSeries()) {
                seriesGroups++;
                int seriesIndex = 0;
                for (XDDFChartData.Series series : data.getSeries()) {
                    seriesIndex++;
                    XDDFDataSource<?> categories = series.getCategoryData();
                    XDDFNumericalDataSource<? extends Number> values = series.getValuesData();
                    if (values == null) continue;
                    int points = Math.min(values.getPointCount(), 20);
                    out.append("系列").append(seriesIndex).append("：");
                    for (int i = 0; i < points; i++) {
                        Object category = null;
                        try {
                            category = categories == null ? null : categories.getPointAt(i);
                        } catch (Exception ignored) {
                        }
                        Object value;
                        try {
                            value = values.getPointAt(i);
                        } catch (Exception e) {
                            continue;
                        }
                        if (i > 0) out.append("，");
                        if (category != null && !String.valueOf(category).isBlank()) {
                            out.append(category).append("=");
                        }
                        out.append(value);
                        totalPoints++;
                    }
                    out.append("\n");
                    if (out.length() > 6000 || totalPoints >= 200) break;
                }
                if (out.length() > 6000 || totalPoints >= 200) break;
            }
        } catch (Exception e) {
            log.debug("提取图表数据失败（忽略）：{}", e.getMessage());
        }
        if (totalPoints > 0 || (title != null && !title.isBlank())) {
            sb.append("[图表]\n").append(out).append("\n");
        }
    }

    private boolean inMerged(Sheet sheet, int row, int col) {
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(row, col)
                    && !(range.getFirstRow() == row && range.getFirstColumn() == col)) return true;
        }
        return false;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** EPUB 本质是 ZIP + XHTML；提取 XHTML/HTML 正文，图片等二进制条目忽略。 */
    private String parseEpub(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null && count < 500) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !(name.endsWith(".xhtml") || name.endsWith(".html")
                        || name.endsWith(".htm"))) continue;
                String html = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                String text = Jsoup.parse(html).text();
                if (!text.isBlank()) {
                    sb.append(text).append('\n');
                    count++;
                }
            }
        }
        return sb.toString();
    }

    private String parsePptx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int index = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("## Slide ").append(index++).append('\n');
                for (var relation : slide.getRelations()) {
                    if (relation instanceof XSLFChart chart) {
                        String title = chart.getTitleShape() == null ? null : chart.getTitleShape().getText();
                        appendChart(sb, chart, title);
                    }
                }
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) sb.append(text).append('\n');
                    } else if (shape instanceof XSLFTable table) {
                        sb.append("<table border=\"1\">\n");
                        for (XSLFTableRow row : table.getRows()) {
                            sb.append("<tr>");
                            for (XSLFTableCell cell : row.getCells()) {
                                int colspan = Math.max(1, cell.getGridSpan());
                                int rowspan = Math.max(1, cell.getRowSpan());
                                sb.append("<td");
                                if (colspan > 1) sb.append(" colspan=\"").append(colspan).append("\"");
                                if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append("\"");
                                sb.append(">").append(escapeHtml(cell.getText() == null ? "" : cell.getText().trim()))
                                        .append("</td>");
                            }
                            sb.append("</tr>\n");
                        }
                        sb.append("</table>\n");
                    } else if (ocrEnabled && shape instanceof XSLFPictureShape picture) {
                        appendOcr(sb, picture.getPictureData().getData(),
                                picture.getPictureData().suggestFileExtension());
                    }
                }
            }
        }
        return sb.toString();
    }

    private boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp") || lower.endsWith(".tif") || lower.endsWith(".tiff")
                || lower.endsWith(".webp");
    }

    private boolean needsOcr(String text) {
        return ocrEnabled && (text == null || text.replaceAll("\\s", "").length() < 20);
    }

    /** 扫描版 PDF OCR：PDFBox 渲染页面为 PNG，再调用外部 tesseract。 */
    private String parsePdfOcr(byte[] bytes, String fileName) throws Exception {
        if (!ocrEnabled) return "";
        StringBuilder sb = new StringBuilder();
        Path tempDir = Files.createTempDirectory("kb-ocr-");
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), Math.max(1, ocrMaxPages));
            for (int i = 0; i < pages; i++) {
                BufferedImage image = preprocessForOcr(
                        renderer.renderImageWithDPI(i, Math.max(72, ocrDpi)));
                image = correctOrientation(image, tempDir.resolve("orient-" + (i + 1) + ".png"));
                Path imagePath = tempDir.resolve("page-" + (i + 1) + ".png");
                ImageIO.write(image, "png", imagePath.toFile());
                OcrTableExtractor.Result ocr = OcrTableExtractor.extract(runOcrTsv(imagePath));
                sb.append("\n## Page ").append(i + 1).append('\n')
                        .append(ocr.text()).append('\n');
                if (!ocr.table().isBlank()) sb.append(ocr.table()).append('\n');
            }
        } finally {
            deleteRecursively(tempDir.toFile());
        }
        log.info("扫描版 PDF OCR 完成：file={} pages={}", fileName, Math.min(ocrMaxPages, 999));
        return sb.toString();
    }

    private String ocrImage(byte[] bytes, String lower) throws Exception {
        if (!ocrEnabled) {
            throw new BusinessException(422, "图片解析需要启用 OCR（knowledge.ocr.enabled=true）");
        }
        Path temp = Files.createTempFile("kb-ocr-", extension(lower));
        try {
            Files.write(temp, bytes);
            return runOcr(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String extension(String lower) {
        int idx = lower.lastIndexOf('.');
        return idx >= 0 ? lower.substring(idx) : ".img";
    }

    private void appendOcr(StringBuilder sb, BufferedImage image, String extension) {
        if (image == null) return;
        Path orientTemp = null;
        try {
            image = preprocessForOcr(image);
            orientTemp = Files.createTempFile("kb-orient-", ".png");
            image = correctOrientation(image, orientTemp);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            appendOcr(sb, out.toByteArray(), extension);
        } catch (Exception e) {
            log.debug("内嵌 PDF 图片编码失败：{}", e.getMessage());
        } finally {
            if (orientTemp != null) {
                try { Files.deleteIfExists(orientTemp); } catch (Exception ignored) { }
            }
        }
    }

    private void appendOcr(StringBuilder sb, byte[] image, String extension) {
        if (!ocrEnabled || image == null || image.length == 0 || sb.length() > 100_000) return;
        try {
            String ext = extension == null || extension.isBlank() ? "png" : extension.toLowerCase(Locale.ROOT);
            if (!ext.startsWith(".")) ext = "." + ext;
            OcrTableExtractor.Result result = ocrImageTable(image, ext);
            if (!result.text().isBlank()) {
                sb.append("\n[图片OCR]\n").append(result.text().trim()).append('\n');
            }
            if (!result.table().isBlank()) sb.append(result.table()).append('\n');
        } catch (Exception e) {
            log.debug("内嵌图片 OCR 跳过：{}", e.getMessage());
        }
    }

    /**
     * 低质量扫描预处理：灰度 + 对比度拉伸；小图放大，放大后再拉伸。
     * 只做基础增强，不替代倾斜校正/去噪等专业图像处理。
     */
    private BufferedImage preprocessForOcr(BufferedImage source) {
        if (!ocrPreprocess || source == null) return source;
        try {
            BufferedImage image = source;
            if (image.getWidth() < 1200 || image.getHeight() < 800) {
                int width = Math.max(1, image.getWidth() * 2);
                int height = Math.max(1, image.getHeight() * 2);
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = scaled.createGraphics();
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, width, height, null);
                graphics.dispose();
                image = scaled;
            }
            BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D graphics = gray.createGraphics();
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            int min = 255;
            int max = 0;
            for (int y = 0; y < gray.getHeight(); y++) {
                for (int x = 0; x < gray.getWidth(); x++) {
                    int value = gray.getRaster().getSample(x, y, 0);
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            if (max - min < 8) return gray;
            double scale = 255.0 / (max - min);
            for (int y = 0; y < gray.getHeight(); y++) {
                for (int x = 0; x < gray.getWidth(); x++) {
                    int value = gray.getRaster().getSample(x, y, 0);
                    gray.getRaster().setSample(x, y, 0,
                            Math.max(0, Math.min(255, (int) ((value - min) * scale))));
                }
            }
            return gray;
        } catch (Exception e) {
            log.debug("OCR 预处理失败（使用原图）：{}", e.getMessage());
            return source;
        }
    }

    /** 扫描页方向纠正：tesseract OSD 检测 90/180/270，OCR 前转回正向。 */
    private BufferedImage correctOrientation(BufferedImage image, Path temp) {
        if (!ocrAutoOrient || image == null) return image;
        try {
            ImageIO.write(image, "png", temp.toFile());
            int rotate = detectRotation(temp);
            if (rotate == 0) return image;
            log.info("扫描页方向纠正：rotate={}°", rotate);
            return rotateImage(image, rotate);
        } catch (Exception e) {
            log.debug("扫描页方向检测失败（使用原图）：{}", e.getMessage());
            return image;
        } finally {
            try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
        }
    }

    private int detectRotation(Path imagePath) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ocrCommand);
        command.add(imagePath.toAbsolutePath().toString());
        command.add("stdout");
        command.add("-l");
        command.add("osd");
        command.add("--psm");
        command.add("0");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PATH", System.getenv().getOrDefault("PATH",
                "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin"));
        Process process = pb.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var in = process.getErrorStream()) {
            in.readAllBytes();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return 0;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Rotate:\\s*(\\d+)").matcher(output);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) % 360 : 0;
    }

    private BufferedImage rotateImage(BufferedImage source, int degrees) {
        double theta = Math.toRadians(degrees);
        double sin = Math.abs(Math.sin(theta));
        double cos = Math.abs(Math.cos(theta));
        int width = (int) Math.floor(source.getWidth() * cos + source.getHeight() * sin);
        int height = (int) Math.floor(source.getWidth() * sin + source.getHeight() * cos);
        BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = rotated.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.translate(width / 2.0, height / 2.0);
        graphics.rotate(theta);
        graphics.drawImage(source, -source.getWidth() / 2, -source.getHeight() / 2, null);
        graphics.dispose();
        return rotated;
    }

    /** 内嵌图片 OCR：TSV 同时用于文本和表格结构。 */
    private OcrTableExtractor.Result ocrImageTable(byte[] bytes, String extension) {
        Path temp = null;
        try {
            String ext = extension == null || extension.isBlank() ? ".png" : extension;
            if (!ext.startsWith(".")) ext = "." + ext;
            temp = Files.createTempFile("kb-ocr-", ext);
            Files.write(temp, bytes);
            return OcrTableExtractor.extract(runOcrTsv(temp));
        } catch (Exception e) {
            log.debug("内嵌图片 OCR 失败：{}", e.getMessage());
            return new OcrTableExtractor.Result("", "");
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
            }
        }
    }

    /** 调用外部 OCR 命令：tesseract <image> stdout -l <langs> [tsv]。 */
    private String runOcr(Path image) throws Exception {
        return runOcr(image, null);
    }

    private String runOcrTsv(Path image) throws Exception {
        return runOcr(image, "tsv");
    }

    private String runOcr(Path image, String config) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ocrCommand);
        command.add(image.toAbsolutePath().toString());
        command.add("stdout");
        if (ocrLanguages != null && !ocrLanguages.isBlank()) {
            command.add("-l");
            command.add(ocrLanguages);
        }
        if (config != null && !config.isBlank()) command.add(config);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin"));
        Process process = pb.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String err;
        try (var in = process.getErrorStream()) {
            err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new BusinessException(504, "OCR 超时（60s）");
        }
        if (process.exitValue() != 0) {
            throw new BusinessException(422, "OCR 失败（exit " + process.exitValue() + "）："
                    + (err.isBlank() ? "请确认已安装 tesseract" : err.substring(0, Math.min(err.length(), 200))));
        }
        return output.trim();
    }

    private void deleteRecursively(File file) {
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) deleteRecursively(child);
            }
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            log.debug("OCR 临时文件清理失败：{}", e.getMessage());
        }
    }

    /** 父块 ~2000 字符，子块按 500/50 切分；每个子块携带父块内容。 */
    private List<ParsedChunk> splitWithParents(String text) {
        String normalized = text.replaceAll("\\r\\n", "\n").trim();
        List<ParsedChunk> result = new ArrayList<>();
        int parentStart = 0;
        int parentIndex = 0;
        while (parentStart < normalized.length()) {
            int parentEnd = Math.min(parentStart + PARENT_SIZE, normalized.length());
            if (parentEnd < normalized.length()) {
                int newline = normalized.lastIndexOf('\n', parentEnd);
                if (newline > parentStart + PARENT_SIZE / 2) parentEnd = newline;
            }
            String parent = normalized.substring(parentStart, parentEnd).trim();
            if (!parent.isEmpty()) {
                for (String child : split(parent)) {
                    result.add(new ParsedChunk(child, parentIndex, parent));
                }
                parentIndex++;
            }
            if (parentEnd >= normalized.length()) break;
            parentStart = Math.max(parentEnd - 100, parentStart + 1);
        }
        return result;
    }

    /** 按字符分段，带重叠（架构 6.3 splitByCharacter）。 */
    private List<String> split(String text) {
        String normalized = text.replaceAll("\\r\\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) break;
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
