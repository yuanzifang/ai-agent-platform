package com.platform.knowledge.service;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.SpreadsheetVersion;
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
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    /** 解析器版本：解析行为/结构化输出变化时递增，用于存量升级和复用判断。 */
    public static final String PARSER_VERSION = "2026-10-26.8";

    private static final long MAX_BYTES = 20 * 1024 * 1024;

    @Value("${knowledge.ocr.enabled:false}")
    private boolean ocrEnabled;
    @Value("${knowledge.ocr.command:tesseract}")
    private String ocrCommand;
    @Value("${knowledge.ocr.languages:chi_sim+eng}")
    private String ocrLanguages;
    @Value("${knowledge.ocr.max-pages:20}")
    private int ocrMaxPages;
    @Value("${knowledge.ocr.concurrency:1}")
    private int ocrConcurrency = 1;
    @Value("${knowledge.ocr.retry-raw-min-chars:10}")
    private int ocrRetryRawMinChars = 10;
    /** 进程内 OCR 结果缓存；0 表示关闭。key 含租户/OCR 命令/语言和图像内容哈希。 */
    @Value("${knowledge.ocr.cache-size:256}")
    private int ocrCacheSize = 256;
    private final Map<String, OcrTableExtractor.Result> ocrCache = new ConcurrentHashMap<>();

    /** 解析级覆盖（上传/知识库/租户/平台配置解析产物）；为空时回落上方 @Value 默认。 */
    private final ThreadLocal<ParseOptions> optsHolder = new ThreadLocal<>();
    private final ThreadLocal<List<String>> warningsHolder = new ThreadLocal<>();

    public record ParseOutcome(List<ParsedChunk> chunks, List<String> warnings) {
    }

    /** 带警告的解析入口：OCR 超限、扫描页无文本等不会静默丢失。 */
    public ParseOutcome parseWithParentsAndWarnings(String fileName, byte[] bytes, ParseOptions opts) {
        warningsHolder.set(new ArrayList<>());
        try {
            return new ParseOutcome(parseWithParents(fileName, bytes, opts),
                    List.copyOf(warningsHolder.get()));
        } finally {
            warningsHolder.remove();
        }
    }

    private void warn(String message) {
        List<String> warnings = warningsHolder.get();
        if (warnings != null && message != null && !message.isBlank()) {
            warnings.add(message);
        }
    }

    /** 带生效配置的解析入口（KnowledgeService 解析链使用）。 */
    public List<ParsedChunk> parseWithParents(String fileName, byte[] bytes, ParseOptions opts) {
        try {
            optsHolder.set(opts == null ? ParseOptions.defaults(
                    ocrEnabled, ocrCommand, ocrLanguages, ocrDpi, ocrMaxPages, false, "", "", 3) : opts);
            return parseWithParents(fileName, bytes);
        } finally {
            optsHolder.remove();
        }
    }

    private ParseOptions opts() {
        ParseOptions o = optsHolder.get();
        return o != null ? o : ParseOptions.defaults(
                ocrEnabled, ocrCommand, ocrLanguages, ocrDpi, ocrMaxPages, false, "", "", 3);
    }

    private PDDocument loadPdf(byte[] bytes) throws Exception {
        String password = opts().pdfPassword();
        return password == null || password.isBlank()
                ? org.apache.pdfbox.Loader.loadPDF(bytes)
                : org.apache.pdfbox.Loader.loadPDF(bytes, password);
    }
    @Value("${knowledge.ocr.dpi:150}")
    private int ocrDpi;
    @Value("${knowledge.ocr.preprocess:true}")
    private boolean ocrPreprocess;
    @Value("${knowledge.ocr.auto-orient:true}")
    private boolean ocrAutoOrient;
    @Value("${knowledge.ocr.deskew:true}")
    private boolean ocrDeskew;
    @Value("${knowledge.ocr.denoise:true}")
    private boolean ocrDenoise;
    @Value("${knowledge.pdf.vector-chart-render-enabled:true}")
    private boolean vectorChartRenderEnabled;
    private final ThreadLocal<Integer> attachmentDepth = ThreadLocal.withInitial(() -> 0);
    private volatile ExecutorService ocrExecutor;

    private ExecutorService ocrExecutor() {
        if (ocrExecutor == null) {
            synchronized (this) {
                if (ocrExecutor == null) {
                    int threads = Math.max(1, Math.min(4, ocrConcurrency));
                    ocrExecutor = Executors.newFixedThreadPool(threads, runnable -> {
                        Thread thread = new Thread(runnable, "knowledge-ocr");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return ocrExecutor;
    }

    @PreDestroy
    void shutdownOcrExecutor() {
        if (ocrExecutor != null) ocrExecutor.shutdownNow();
    }

    public record ParsedChunk(String content, int parentIndex, String parentContent,
                              String embeddingText, int pageFrom, int pageTo) {

        /** 兼容旧构造：embedding 文本按去 Markdown/HTML 纯文本生成，页码未知。 */
        public ParsedChunk(String content, int parentIndex, String parentContent) {
            this(content, parentIndex, parentContent, MarkdownText.toPlain(content), 0, 0);
        }

        public String embeddingContent() {
            return embeddingText == null || embeddingText.isBlank()
                    ? MarkdownText.toPlain(content) : embeddingText;
        }
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
                try (PDDocument doc = loadPdf(bytes)) {
                    PDFRenderer renderer = new PDFRenderer(doc);
                    int count = 0;
                    Set<COSDictionary> seen = new HashSet<>();
                    for (int p = 0; p < doc.getNumberOfPages() && count < 20; p++) {
                        PDPage page = doc.getPage(p);
                        PDResources resources = page.getResources();
                        if (resources != null) {
                            count = collectPdfImages(resources, images, count, 0, seen);
                        }
                        // 内联图片（BI...ID...EI）不在 XObject 资源表中，需走内容流。
                        if (count < 20) {
                            try {
                                PdfInlineImageCollector collector = new PdfInlineImageCollector(page);
                                collector.processPage(page);
                                for (byte[] data : collector.images()) {
                                    if (count >= 20) break;
                                    addImage(images, data, "png");
                                    count++;
                                }
                            } catch (Exception e) {
                                log.debug("PDF 内联图片提取跳过：{}", e.getMessage());
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

    /** 递归提取 XObject 图片；Form XObject 内部常嵌套位图，只遍历顶层会漏图。 */
    private int collectPdfImages(PDResources resources, List<ExtractedImage> images,
                                 int count, int depth, Set<COSDictionary> seen) {
        if (resources == null || depth > 4 || count >= 20) return count;
        try {
            for (var name : resources.getXObjectNames()) {
                if (count >= 20) break;
                PDXObject object = resources.getXObject(name);
                if (object instanceof PDImageXObject image) {
                    COSDictionary key = image.getCOSObject();
                    if (key != null && !seen.add(key)) continue;
                    try {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        ImageIO.write(image.getImage(), "png", out);
                        addImage(images, out.toByteArray(), "png");
                        count++;
                    } catch (Exception e) {
                        log.debug("PDF 图片解码跳过：{}", e.getMessage());
                    }
                } else if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    count = collectPdfImages(form.getResources(), images, count, depth + 1, seen);
                }
            }
        } catch (Exception e) {
            log.debug("PDF XObject 图片遍历跳过：{}", e.getMessage());
        }
        return count;
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
            else if (lower.endsWith(".pdf")) text = parsePdf(bytes);
            else if (lower.endsWith(".xlsx")) text = parseExcel(bytes, false);
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
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new BusinessException(422, "PDF 已加密，请在上传解析设置中填写 PDF 密码（密码错误或未提供）");
        } catch (Exception e) {
            throw new BusinessException(422, "文档解析失败：" + e.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException(422, opts().ocrEnabled() ? "文档内容为空（OCR 未识别到文本）"
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
            if (opts().ocrEnabled()) {
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
        try (PDDocument doc = loadPdf(bytes)) {
            PdfMarkdownConverter converter = new PdfMarkdownConverter(opts(), this::ocrPage, this::warn,
                    Math.max(1, ocrConcurrency));
            StringBuilder sb = new StringBuilder(converter.convert(doc));
            appendPdfMetadata(doc, sb);
            appendPdfFormsAndAnnotations(doc, sb);
            appendPdfOutlineAndLinks(doc, sb);
            appendPdfAttachments(doc, sb);
            appendEmbeddedImageOcr(doc, sb, converter.ocrPages());
            return sb.toString();
        }
    }

    /** 单页 OCR：PDF 渲染串行加锁，tesseract/预处理可在页面级并发。 */
    private PdfMarkdownConverter.OcrResult ocrPage(PDDocument doc, int pageIndex,
                                                   ParseOptions options) throws Exception {
        ParseOptions previous = optsHolder.get();
        optsHolder.set(options);
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage direct;
            BufferedImage base;
            // PDFBox 的 PDDocument/PDFRenderer 不是线程安全的；只把渲染段放进锁，
            // 预处理、方向纠正和 tesseract 可以并发执行。
            synchronized (doc) {
                direct = singleLargePageImage(doc, pageIndex);
                base = direct != null ? direct
                        : renderer.renderImageWithDPI(pageIndex, Math.max(72, opts().ocrDpi()));
            }
            boolean enhanced = direct == null || ocrPreprocess || ocrAutoOrient || ocrDeskew;
            PdfMarkdownConverter.OcrResult result = ocrPageVariant(base, direct == null, true);
            if (enhanced) {
                int textLength = result.text() == null ? 0
                        : result.text().replaceAll("\\s", "").length();
                boolean lowQuality = textLength < Math.max(0, ocrRetryRawMinChars)
                        && (result.tableHtml() == null || result.tableHtml().isBlank());
                if (lowQuality) {
                    PdfMarkdownConverter.OcrResult raw = ocrPageVariant(base, direct == null, false);
                    int rawLength = raw.text() == null ? 0
                            : raw.text().replaceAll("\\s", "").length();
                    boolean betterTable = (result.tableHtml() == null || result.tableHtml().isBlank())
                            && raw.tableHtml() != null && !raw.tableHtml().isBlank();
                    if (rawLength > textLength || betterTable) result = raw;
                }
            }
            return result;
        } finally {
            if (previous == null) optsHolder.remove();
            else optsHolder.set(previous);
        }
    }

    private PdfMarkdownConverter.OcrResult ocrPageVariant(BufferedImage base, boolean crop,
                                                          boolean enhance) throws Exception {
        OcrTableExtractor.Result result = ocrBufferedImage(base, crop, enhance, "kb-page-");
        return new PdfMarkdownConverter.OcrResult(result.text(), result.table());
    }

    /** 页面/内嵌图片共用的 OCR 流水线：预处理后按图像内容哈希查缓存。 */
    private OcrTableExtractor.Result ocrBufferedImage(BufferedImage base, boolean crop,
                                                       boolean enhance, String prefix) throws Exception {
        BufferedImage image = base;
        Path orientTemp = null;
        try {
            if (enhance) {
                if (crop) image = cropForOcr(image);
                image = preprocessForOcr(image);
                orientTemp = Files.createTempFile(prefix + "orient-", ".png");
                image = correctOrientation(image, orientTemp);
                image = deskewForOcr(image);
            }
            return runOcrWithCache(image);
        } finally {
            if (orientTemp != null) {
                try { Files.deleteIfExists(orientTemp); } catch (Exception ignored) { }
            }
        }
    }

    private OcrTableExtractor.Result runOcrWithCache(BufferedImage image) throws Exception {
        Path imagePath = Files.createTempFile("kb-ocr-", ".png");
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            byte[] png = buffer.toByteArray();
            String cacheKey = ocrCacheKey(png);
            if (ocrCacheSize > 0) {
                OcrTableExtractor.Result cached = ocrCache.get(cacheKey);
                if (cached != null) return cached;
            }
            Files.write(imagePath, png);
            OcrTableExtractor.Result result = OcrTableExtractor.extract(runOcrTsv(imagePath), image);
            if (ocrCacheSize > 0 && result != null) {
                if (ocrCache.size() >= ocrCacheSize) ocrCache.clear();
                ocrCache.put(cacheKey, result);
            }
            return result;
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    private String ocrCacheKey(byte[] png) throws Exception {
        String prefix = String.valueOf(TenantContext.getTenantId()) + "|"
                + opts().ocrCommand() + "|" + opts().ocrLanguages() + "|";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return prefix + HexFormat.of().formatHex(digest.digest(png));
    }

    /** 内嵌位图 OCR；Form XObject 内嵌的图片也递归处理。整页已 OCR 的页面跳过，避免重复。 */
    private void appendEmbeddedImageOcr(PDDocument doc, StringBuilder sb, Set<Integer> ocrPages) {
        if (!opts().ocrEnabled()) return;
        StringBuilder tables = new StringBuilder();
        int[] count = {0};
        Set<COSDictionary> seen = new HashSet<>();
        if (ocrConcurrency <= 1) {
            for (int p = 0; p < doc.getNumberOfPages() && count[0] < 20; p++) {
                if (ocrPages.contains(p)) continue;
                PDResources resources = doc.getPage(p).getResources();
                if (resources != null) collectImageOcr(resources, sb, tables, count, 0, seen);
            }
        } else {
            List<BufferedImage> images = new ArrayList<>();
            for (int p = 0; p < doc.getNumberOfPages() && count[0] < 20; p++) {
                if (ocrPages.contains(p)) continue;
                PDResources resources = doc.getPage(p).getResources();
                if (resources != null) collectImagesForOcr(resources, images, count, 0, seen);
            }
            List<Future<OcrTableExtractor.Result>> futures = new ArrayList<>(images.size());
            ParseOptions options = opts();
            for (BufferedImage image : images) {
                futures.add(ocrExecutor().submit(() -> ocrImageResult(image, options)));
            }
            for (Future<OcrTableExtractor.Result> future : futures) {
                try {
                    OcrTableExtractor.Result result = future.get();
                    if (!result.text().isBlank()) {
                        sb.append("\n[图片OCR]\n").append(result.text().trim()).append('\n');
                    }
                    if (!result.table().isBlank()) {
                        tables = new StringBuilder(OcrTableExtractor.mergeTables(
                                tables.toString(), result.table()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    log.debug("内嵌图片 OCR 任务失败：{}", e.getCause() == null
                            ? e.getMessage() : e.getCause().getMessage());
                }
            }
        }
        if (!tables.isEmpty()) sb.append('\n').append(tables).append('\n');
    }

    private void collectImagesForOcr(PDResources resources, List<BufferedImage> images,
                                     int[] count, int depth, Set<COSDictionary> seen) {
        if (resources == null || depth > 4 || count[0] >= 20) return;
        try {
            for (var name : resources.getXObjectNames()) {
                if (count[0] >= 20) return;
                PDXObject object = resources.getXObject(name);
                if (object instanceof PDImageXObject image) {
                    COSDictionary key = image.getCOSObject();
                    if (key != null && !seen.add(key)) continue;
                    if (image.getWidth() < 200 || image.getHeight() < 200) continue;
                    try {
                        images.add(image.getImage());
                        count[0]++;
                    } catch (Exception e) {
                        log.debug("PDF 图片解码跳过：{}", e.getMessage());
                    }
                } else if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    collectImagesForOcr(form.getResources(), images, count, depth + 1, seen);
                }
            }
        } catch (Exception e) {
            log.debug("PDF 内嵌图片收集跳过：{}", e.getMessage());
        }
    }

    /** 单张图片 OCR（图片已解码）；供并发任务使用，线程安全。 */
    private OcrTableExtractor.Result ocrImageResult(BufferedImage source, ParseOptions options) {
        if (source == null || options == null || !options.ocrEnabled()) {
            return new OcrTableExtractor.Result("", "");
        }
        optsHolder.set(options);
        try {
            return ocrBufferedImage(source, false, true, "kb-image-");
        } catch (Exception e) {
            log.debug("内嵌图片并发 OCR 跳过：{}", e.getMessage());
            return new OcrTableExtractor.Result("", "");
        } finally {
            optsHolder.remove();
        }
    }

    private void collectImageOcr(PDResources resources, StringBuilder sb, StringBuilder tables,
                                 int[] count, int depth, Set<COSDictionary> seen) {
        if (resources == null || depth > 4) return;
        try {
            for (var name : resources.getXObjectNames()) {
                if (count[0] >= 20) return;
                PDXObject object = resources.getXObject(name);
                if (object instanceof PDImageXObject image) {
                    COSDictionary key = image.getCOSObject();
                    if (key != null && !seen.add(key)) continue;
                    // 页眉 logo、图标等小图不进入 OCR，避免占满 20 张上限。
                    if (image.getWidth() < 200 || image.getHeight() < 200) continue;
                    appendOcr(sb, image.getImage(), "png", tables);
                    count[0]++;
                } else if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    collectImageOcr(form.getResources(), sb, tables, count, depth + 1, seen);
                }
            }
        } catch (Exception e) {
            log.debug("PDF 内嵌图片 OCR 跳过：{}", e.getMessage());
        }
    }

    /** PDF 元数据：标题/作者/主题/关键词/页数，便于检索与溯源。 */
    private void appendPdfMetadata(PDDocument doc, StringBuilder sb) {
        try {
            var info = doc.getDocumentInformation();
            StringBuilder meta = new StringBuilder();
            appendMeta(meta, "标题", info.getTitle());
            appendMeta(meta, "作者", info.getAuthor());
            appendMeta(meta, "主题", info.getSubject());
            appendMeta(meta, "关键词", info.getKeywords());
            if (meta.length() > 0) {
                sb.append("\n[PDF元数据] 页数: ").append(doc.getNumberOfPages()).append("; ")
                        .append(meta).append('\n');
            }
        } catch (Exception e) {
            log.debug("PDF 元数据读取失败（忽略）：{}", e.getMessage());
        }
    }

    private void appendMeta(StringBuilder meta, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (meta.length() > 0) meta.append("; ");
        meta.append(label).append(": ").append(value.trim());
    }

    private int resolveDestinationPage(PDDocument doc,
                                       org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination destination) {
        try {
            if (destination instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination pageDest
                    && pageDest.getPage() != null) {
                return doc.getPages().indexOf(pageDest.getPage()) + 1;
            }
            if (destination instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination named) {
                var names = doc.getDocumentCatalog().getNames();
                if (names == null || names.getDests() == null) return -1;
                var pageDest = names.getDests().getValue(named.getNamedDestination());
                if (pageDest != null && pageDest.getPage() != null) {
                    return doc.getPages().indexOf(pageDest.getPage()) + 1;
                }
            }
        } catch (Exception e) {
            log.debug("PDF 命名目标解析失败（忽略）：{}", e.getMessage());
        }
        return -1;
    }

    /** PDF 内嵌附件：至少保留附件名称，正文内容仍由附件本身管理。 */
    private void appendPdfAttachments(PDDocument doc, StringBuilder sb) {
        try {
            var names = doc.getDocumentCatalog().getNames();
            if (names == null || names.getEmbeddedFiles() == null) return;
            var files = names.getEmbeddedFiles().getNames();
            if (files == null) return;
            int depth = attachmentDepth.get();
            int count = 0;
            for (var entry : files.entrySet()) {
                if (count >= 5) break;
                String name = entry.getKey();
                sb.append("\n[附件]").append(name).append('\n');
                if (depth >= 1) continue;
                try {
                    var embedded = entry.getValue() == null ? null : entry.getValue().getEmbeddedFile();
                    if (embedded == null || embedded.getSize() > 5 * 1024 * 1024) continue;
                    byte[] data = embedded.toByteArray();
                    attachmentDepth.set(depth + 1);
                    try {
                        String content = parseWithParents(name, data).stream()
                                .map(ParsedChunk::content)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        if (!content.isBlank()) {
                            sb.append("[附件正文]").append(name).append('\n');
                            sb.append(content, 0, Math.min(content.length(), 5000)).append('\n');
                        }
                    } finally {
                        attachmentDepth.set(depth);
                    }
                } catch (Exception e) {
                    log.debug("PDF 附件正文解析跳过 {}：{}", name, e.getMessage());
                }
                count++;
            }
        } catch (Exception e) {
            log.debug("PDF 附件读取失败（忽略）：{}", e.getMessage());
        }
    }

    /** PDF 书签目录与超链接：保留文档结构和外部链接，便于检索/跳转。 */
    private void appendPdfOutlineAndLinks(PDDocument doc, StringBuilder sb) {
        try {
            var outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                int[] count = {0};
                appendOutlineItems(outline.getFirstChild(), sb, count, doc);
            }
        } catch (Exception e) {
            log.debug("PDF 书签读取失败（忽略）：{}", e.getMessage());
        }
        try {
            for (var page : doc.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    if (!(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link)) continue;
                    var action = link.getAction();
                    if (action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri
                            && uri.getURI() != null && !uri.getURI().isBlank()) {
                        sb.append("\n[链接]").append(uri.getURI().trim()).append('\n');
                    } else if (action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo goTo) {
                        int number = resolveDestinationPage(doc, goTo.getDestination());
                        if (number > 0) sb.append("\n[跳转]第").append(number).append("页\n");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PDF 链接读取失败（忽略）：{}", e.getMessage());
        }
    }

    private void appendOutlineItems(org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item,
                                    StringBuilder sb, int[] count, PDDocument doc) {
        if (item == null || count[0] >= 100) return;
        try {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                sb.append("\n[书签]").append(title.trim());
                try {
                    var page = item.findDestinationPage(doc);
                    if (page != null) {
                        int number = doc.getPages().indexOf(page) + 1;
                        if (number > 0) sb.append("（第").append(number).append("页）");
                    }
                } catch (Exception ignored) {
                }
                sb.append('\n');
                count[0]++;
            }
            appendOutlineItems(item.getFirstChild(), sb, count, doc);
            appendOutlineItems(item.getNextSibling(), sb, count, doc);
        } catch (Exception ignored) {
        }
    }

    /** PDF 表单字段/批注：表单值不进入正文文本层时，作为补充知识进入解析结果。 */
    private void appendPdfFormsAndAnnotations(PDDocument doc, StringBuilder sb) {
        try {
            var acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                for (var field : acroForm.getFieldTree()) {
                    try {
                        String value = field.getValueAsString();
                        String name = field.getFullyQualifiedName();
                        if (name != null && value != null && !value.isBlank()) {
                            sb.append("\n[表单]").append(name.trim()).append(": ").append(value.trim()).append('\n');
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (acroForm.hasXFA()) {
                    try {
                        byte[] xfa = acroForm.getXFA().getBytes();
                        if (xfa != null && xfa.length > 0) sb.append(parseXfa(xfa));
                    } catch (Exception e) {
                        log.debug("PDF XFA 读取失败（忽略）：{}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PDF 表单字段读取失败（忽略）：{}", e.getMessage());
        }
        try {
            for (var page : doc.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    try {
                        String contents = annotation.getContents();
                        if (contents == null || contents.isBlank()) continue;
                        String subtype = annotation.getSubtype();
                        sb.append("\n[批注]").append(subtype == null ? "ANNOTATION" : subtype)
                                .append(": ").append(contents.trim()).append('\n');
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PDF 批注读取失败（忽略）：{}", e.getMessage());
        }
    }

    /** XFA 原文 + 动态数据集路径；渲染型版面仍无法在服务端完整还原。 */
    private String parseXfa(byte[] bytes) {
        String xml = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        try {
            String text = Jsoup.parse(xml, "", Parser.xmlParser()).text();
            if (!text.isBlank()) {
                out.append("\n[XFA]").append(text, 0, Math.min(text.length(), 8000)).append('\n');
            }
        } catch (Exception ignored) {
            // 原文解析失败时仍尝试 DOM 数据集提取
        }
        List<String> fields = xfaFields(xml);
        if (!fields.isEmpty()) {
            out.append("\n[XFA数据]\n");
            for (String field : fields) out.append(field).append('\n');
        }
        List<String> templateFields = xfaTemplateFields(xml);
        if (!templateFields.isEmpty()) {
            out.append("\n[XFA字段]\n");
            for (String field : templateFields) out.append(field).append('\n');
        }
        return out.toString();
    }

    private List<String> xfaFields(String xml) {
        List<String> fields = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document dom = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList datasets = dom.getElementsByTagNameNS("*", "datasets");
            if (datasets.getLength() == 0) datasets = dom.getElementsByTagNameNS("*", "data");
            if (datasets.getLength() == 0) return fields;
            Element start = (Element) datasets.item(0);
            Element data = firstChild(start, "data");
            if (data != null) start = data;
            NodeList children = start.getChildNodes();
            for (int i = 0; i < children.getLength() && fields.size() < 200; i++) {
                if (children.item(i) instanceof Element child) collectXfa(child, "", fields);
            }
        } catch (Exception e) {
            log.debug("XFA 数据集解析失败（忽略）：{}", e.getMessage());
        }
        return fields;
    }

    private void collectXfa(Element element, String prefix, List<String> fields) {
        if (fields.size() >= 200) return;
        String name = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        int occurrence = 1;
        Node sibling = element.getPreviousSibling();
        while (sibling != null) {
            if (sibling instanceof Element prev) {
                String prevName = prev.getLocalName() == null ? prev.getNodeName() : prev.getLocalName();
                if (name.equals(prevName)) occurrence++;
            }
            sibling = sibling.getPreviousSibling();
        }
        String segment = name + (occurrence > 1 ? "[" + occurrence + "]" : "");
        String path = prefix.isEmpty() ? segment : prefix + "." + segment;
        boolean hasElementChild = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                hasElementChild = true;
                collectXfa(child, path, fields);
            }
        }
        if (!hasElementChild) {
            String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
            if (!value.isBlank()) {
                if (value.length() > 500) value = value.substring(0, 500);
                fields.add(path + ": " + value);
            }
        }
    }

    /** 提取 XFA template 中字段路径/标签，适用于 datasets 为空的动态表单。 */
    private List<String> xfaTemplateFields(String xml) {
        List<String> fields = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document dom = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList fieldNodes = dom.getElementsByTagNameNS("*", "field");
            for (int i = 0; i < fieldNodes.getLength() && fields.size() < 200; i++) {
                if (!(fieldNodes.item(i) instanceof Element field)) continue;
                String name = field.getAttribute("name");
                if (name == null || name.isBlank()) {
                    String ref = textOfFirst(field, "ref");
                    name = ref == null ? "" : ref;
                }
                if (name.isBlank()) continue;
                String path = xfaFieldPath(field);
                String caption = textOfFirst(field, "caption");
                fields.add(path + (caption == null || caption.isBlank() ? "" : " (" + caption + ")"));
            }
        } catch (Exception e) {
            log.debug("XFA template 字段解析失败（忽略）：{}", e.getMessage());
        }
        return fields;
    }

    private String xfaFieldPath(Element field) {
        java.util.ArrayDeque<String> names = new java.util.ArrayDeque<>();
        String fieldName = field.getAttribute("name");
        if (fieldName != null && !fieldName.isBlank()) names.addFirst(fieldName);
        Node parent = field.getParentNode();
        while (parent instanceof Element element) {
            String local = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
            if ("subform".equals(local)) {
                String name = element.getAttribute("name");
                if (name != null && !name.isBlank()) names.addFirst(name);
            }
            parent = parent.getParentNode();
        }
        return String.join(".", names);
    }

    private String textOfFirst(Element root, String localName) {
        NodeList all = root.getElementsByTagNameNS("*", localName);
        if (all.getLength() == 0) return "";
        String text = all.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                if (localName.equals(name)) return child;
            }
        }
        return null;
    }

    private void setXmlFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // 不同 JAXP 实现支持的特性不同，安全默认值仍由其余配置保证
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
                            appendChart(sb, chart, title, workbook);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private void appendChart(StringBuilder sb, XDDFChart chart, String title, Workbook workbook) {
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
            String sourceCheck = validateChartSources(chart, workbook);
            if (sourceCheck != null) out.append(sourceCheck).append("\n");
            sb.append("[图表]\n").append(out).append("\n");
        }
    }

    /** 将图表系列缓存值与源单元格逐点比对；仅在有可定位单元格时输出结果。 */
    private String validateChartSources(XDDFChart chart, Workbook workbook) {
        if (workbook == null) return null;
        int checked = 0;
        int mismatch = 0;
        try {
            for (XDDFChartData data : chart.getChartSeries()) {
                for (XDDFChartData.Series series : data.getSeries()) {
                    XDDFNumericalDataSource<? extends Number> values = series.getValuesData();
                    if (values == null || !values.isCellRange()) continue;
                    String reference = values.getDataRangeReference();
                    if (reference == null || reference.isBlank()) continue;
                    AreaReference area = new AreaReference(reference, SpreadsheetVersion.EXCEL2007);
                    CellReference first = area.getFirstCell();
                    CellReference last = area.getLastCell();
                    org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet(first.getSheetName());
                    if (sheet == null) continue;
                    int rows = last.getRow() - first.getRow() + 1;
                    int cols = last.getCol() - first.getCol() + 1;
                    int count = Math.min(values.getPointCount(), Math.min(200, rows * cols));
                    for (int i = 0; i < count; i++) {
                        int row = first.getRow() + i / cols;
                        int col = first.getCol() + i % cols;
                        org.apache.poi.ss.usermodel.Row sourceRow = sheet.getRow(row);
                        if (sourceRow == null) continue;
                        org.apache.poi.ss.usermodel.Cell cell = sourceRow.getCell(col);
                        if (cell == null || cell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC) continue;
                        Number expected = values.getPointAt(i);
                        if (expected == null) continue;
                        checked++;
                        if (Math.abs(expected.doubleValue() - cell.getNumericCellValue()) > 0.000001) mismatch++;
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return checked == 0 ? null : "[图表源校验] 已核对 " + checked + " 个点，差异 " + mismatch + " 个";
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
                        try {
                            appendChart(sb, chart, title, chart.getWorkbook());
                        } catch (Exception ignored) {
                            appendChart(sb, chart, title, null);
                        }
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
                    } else if (opts().ocrEnabled() && shape instanceof XSLFPictureShape picture) {
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

    /** 图表交叉校验用 OCR：只读取图片文字，不返回表格结构；失败时返回空串。 */
    public String ocrText(byte[] bytes, String extension, ParseOptions options) {
        if (bytes == null || bytes.length == 0 || options == null || !options.ocrEnabled()) return "";
        optsHolder.set(options);
        Path temp = null;
        try {
            String ext = extension == null || extension.isBlank() ? "png" : extension.toLowerCase(Locale.ROOT);
            if (!ext.startsWith(".")) ext = "." + ext;
            temp = Files.createTempFile("kb-chart-ocr-", ext);
            Files.write(temp, bytes);
            return runOcr(temp);
        } catch (Exception e) {
            log.debug("图表 OCR 交叉校验失败（忽略）：{}", e.getMessage());
            return "";
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
            }
            optsHolder.remove();
        }
    }

    private String ocrImage(byte[] bytes, String lower) throws Exception {
        if (!opts().ocrEnabled()) {
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
        appendOcr(sb, image, extension, null);
    }

    private void appendOcr(StringBuilder sb, BufferedImage image, String extension,
                           StringBuilder tableAccumulator) {
        if (image == null) return;
        try {
            image = preprocessForOcr(image);
            Path orientTemp = Files.createTempFile("kb-orient-", ".png");
            image = correctOrientation(image, orientTemp);
            image = deskewForOcr(image);
            Files.deleteIfExists(orientTemp);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            appendOcr(sb, out.toByteArray(), extension, tableAccumulator);
        } catch (Exception e) {
            log.debug("内嵌 PDF 图片编码失败：{}", e.getMessage());
        }
    }

    private void appendOcr(StringBuilder sb, byte[] image, String extension) {
        appendOcr(sb, image, extension, null);
    }

    private void appendOcr(StringBuilder sb, byte[] image, String extension,
                           StringBuilder tableAccumulator) {
        if (!opts().ocrEnabled() || image == null || image.length == 0 || sb.length() > 100_000) return;
        try {
            String ext = extension == null || extension.isBlank() ? "png" : extension.toLowerCase(Locale.ROOT);
            if (!ext.startsWith(".")) ext = "." + ext;
            OcrTableExtractor.Result result = ocrImageTable(image, ext);
            if (!result.text().isBlank()) {
                sb.append("\n[图片OCR]\n").append(result.text().trim()).append('\n');
            }
            if (!result.table().isBlank()) {
                if (tableAccumulator == null) {
                    sb.append(result.table()).append('\n');
                } else {
                    String merged = OcrTableExtractor.mergeTables(tableAccumulator.toString(), result.table());
                    tableAccumulator.setLength(0);
                    tableAccumulator.append(merged);
                }
            }
        } catch (Exception e) {
            log.debug("内嵌图片 OCR 跳过：{}", e.getMessage());
        }
    }

    /** 单页扫描件通常是一张整页图片，直接 OCR 原图可保留分辨率与方向信息。 */
    private BufferedImage singleLargePageImage(PDDocument doc, int pageIndex) {
        List<BufferedImage> images = new ArrayList<>();
        collectPageImages(doc.getPage(pageIndex).getResources(), images, 0, new HashSet<>());
        if (images.size() != 1) return null;
        BufferedImage image = images.get(0);
        return image.getWidth() >= 300 && image.getHeight() >= 300 ? image : null;
    }

    private void collectPageImages(PDResources resources, List<BufferedImage> images, int depth,
                                   Set<COSDictionary> seen) {
        if (resources == null || depth > 4 || images.size() > 1) return;
        try {
            for (var name : resources.getXObjectNames()) {
                if (images.size() > 1) return;
                PDXObject object = resources.getXObject(name);
                if (object instanceof PDImageXObject image) {
                    COSDictionary key = image.getCOSObject();
                    if (key != null && !seen.add(key)) continue;
                    try {
                        images.add(image.getImage());
                    } catch (Exception ignored) {
                        // 解码失败的图片不影响其余页
                    }
                } else if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    collectPageImages(form.getResources(), images, depth + 1, seen);
                }
            }
        } catch (Exception e) {
            log.debug("PDF 页面图片读取跳过：{}", e.getMessage());
        }
    }

    /** 去掉整页扫描四周的空白边，提升 OSD 方向检测与 OCR 表格识别的稳定性。 */
    private BufferedImage cropForOcr(BufferedImage source) {
        if (source == null) return null;
        try {
            int minX = source.getWidth(), minY = source.getHeight(), maxX = -1, maxY = -1;
            for (int y = 0; y < source.getHeight(); y += 2) {
                for (int x = 0; x < source.getWidth(); x += 2) {
                    int rgb = source.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                    if (r + g + b < 735) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX <= minX || maxY <= minY) return source;
            int margin = 12;
            minX = Math.max(0, minX - margin);
            minY = Math.max(0, minY - margin);
            maxX = Math.min(source.getWidth() - 1, maxX + margin);
            maxY = Math.min(source.getHeight() - 1, maxY + margin);
            int width = maxX - minX + 1, height = maxY - minY + 1;
            if (width >= source.getWidth() * 0.95 && height >= source.getHeight() * 0.95) {
                return source;
            }
            return source.getSubimage(minX, minY, width, height);
        } catch (Exception e) {
            return source;
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
            gray = denoiseIfNeeded(gray);
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

    /**
     * 小角度倾斜校正：灰度投影方差，在 -8°～8° 内寻找文字行最水平的旋转角。
     * 只校正明显倾斜（>=1°），避免对正常扫描反复旋转。
     */
    private BufferedImage deskewForOcr(BufferedImage source) {
        if (!ocrDeskew || source == null) return source;
        try {
            BufferedImage small = source;
            if (small.getWidth() > 900) {
                int width = 900;
                int height = Math.max(1, (int) (small.getHeight() * (900.0 / small.getWidth())));
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(small, 0, 0, width, height, null);
                g.dispose();
                small = scaled;
            }
            double base = projectionScore(small);
            double bestScore = base;
            int bestAngle = 0;
            for (int angle = -8; angle <= 8; angle++) {
                if (angle == 0) continue;
                BufferedImage rotated = rotateImage(small, angle);
                double score = projectionScore(rotated);
                if (score > bestScore) {
                    bestScore = score;
                    bestAngle = angle;
                }
            }
            if (bestAngle == 0 || bestScore < base * 1.05) return source;
            log.info("扫描页倾斜校正：angle={}°", bestAngle);
            return rotateImage(source, bestAngle);
        } catch (Exception e) {
            log.debug("扫描页倾斜校正失败（使用原图）：{}", e.getMessage());
            return source;
        }
    }

    private double projectionScore(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rows = new int[height];
        for (int y = 0; y < height; y++) {
            int count = 0;
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int gray = ((rgb >> 16) & 0xff) * 30 + ((rgb >> 8) & 0xff) * 59 + (rgb & 0xff) * 11;
                if (gray / 100 < 180) count++;
            }
            rows[y] = count;
        }
        double mean = 0;
        for (int value : rows) mean += value;
        mean /= Math.max(1, rows.length);
        double variance = 0;
        for (int value : rows) variance += (value - mean) * (value - mean);
        return variance;
    }

    /** 检测椒盐噪声，占比超过 0.2% 时做 3x3 中值滤波，避免正常文本被过度模糊。 */
    private BufferedImage denoiseIfNeeded(BufferedImage gray) {
        if (!ocrDenoise) return gray;
        int width = gray.getWidth();
        int height = gray.getHeight();
        int noisy = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int value = gray.getRaster().getSample(x, y, 0);
                int bright = 0;
                int dark = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int neighbor = gray.getRaster().getSample(x + dx, y + dy, 0);
                        if (neighbor > 200) bright++;
                        else if (neighbor < 60) dark++;
                    }
                }
                if ((value < 40 && bright >= 6) || (value > 220 && dark >= 6)) noisy++;
            }
        }
        if (noisy < Math.max(20, width * height / 500)) return gray;
        BufferedImage cleaned = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        int radius = noisy > width * height / 200 ? 2 : 1;
        int side = radius * 2 + 1;
        int[] window = new int[side * side];
        for (int y = radius; y < height - radius; y++) {
            for (int x = radius; x < width - radius; x++) {
                int index = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        window[index++] = gray.getRaster().getSample(x + dx, y + dy, 0);
                    }
                }
                java.util.Arrays.sort(window);
                cleaned.getRaster().setSample(x, y, 0, window[window.length / 2]);
            }
        }
        log.info("扫描页去噪：noisePixels={} size={}x{}", noisy, width, height);
        return cleaned;
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
        command.add(opts().ocrCommand());
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
        // 同 runOcr：流消费与进程等待必须并行，否则 OSD 进程 hang 时 30s 超时失效
        var outputFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> drainStream(process.getInputStream()));
        var errFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> drainStream(process.getErrorStream()));
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return 0;
        }
        String output;
        try {
            output = outputFuture.get(5, TimeUnit.SECONDS);
            errFuture.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
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
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            return OcrTableExtractor.extract(runOcrTsv(temp), source);
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
        command.add(opts().ocrCommand());
        command.add(image.toAbsolutePath().toString());
        command.add("stdout");
        if (opts().ocrLanguages() != null && !opts().ocrLanguages().isBlank()) {
            command.add("-l");
            command.add(opts().ocrLanguages());
        }
        if (config != null && !config.isBlank()) command.add(config);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin"));
        Process process = pb.start();
        // 流必须在等待进程结束的同时由后台线程消费：同步 readAllBytes 会阻塞到进程关闭流，
        // 进程 hang 时下方 waitFor 超时永远走不到；而先 wait 后读又会因 TSV 输出超管道缓冲反向死锁。
        var outputFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> drainStream(process.getInputStream()));
        var errFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> drainStream(process.getErrorStream()));
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new BusinessException(504, "OCR 超时（60s）");
        }
        String output;
        String err;
        try {
            output = outputFuture.get(5, TimeUnit.SECONDS);
            err = errFuture.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new BusinessException(500, "OCR 输出读取失败：" + e.getCause().getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new BusinessException(500, "OCR 输出读取超时");
        }
        if (process.exitValue() != 0) {
            throw new BusinessException(422, "OCR 失败（exit " + process.exitValue() + "）："
                    + (err.isBlank() ? "请确认已安装 tesseract" : err.substring(0, Math.min(err.length(), 200))));
        }
        return output.trim();
    }

    /** 消费子进程输出流为字符串；流随进程终止必然 EOF，读失败按空处理不放大故障。 */
    private String drainStream(java.io.InputStream in) {
        try (var stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
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

    /** 结构化分块：Markdown 标题/段落优先，表格不拆；父块 ~2000、子块 ~500/50。 */
    private List<ParsedChunk> splitWithParents(String text) {
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.split(text);
        List<ParsedChunk> result = new ArrayList<>(chunks.size());
        for (MarkdownChunker.Chunk chunk : chunks) {
            result.add(new ParsedChunk(chunk.content(), chunk.parentIndex(), chunk.parentContent(),
                    chunk.embeddingText(), chunk.pageFrom(), chunk.pageTo()));
        }
        return result;
    }
}
