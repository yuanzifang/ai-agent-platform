package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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

    public record ParsedChunk(String content, int parentIndex, String parentContent) {
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
            else if (lower.endsWith(".pdf")) {
                text = parsePdf(bytes);
                if (needsOcr(text)) text = parsePdfOcr(bytes, fileName);
            } else if (lower.endsWith(".xlsx")) text = parseExcel(bytes, false);
            else if (lower.endsWith(".xls")) text = parseExcel(bytes, true);
            else if (lower.endsWith(".pptx")) text = parsePptx(bytes);
            else if (lower.endsWith(".html") || lower.endsWith(".htm")) text = Jsoup.parse(
                    new String(bytes, StandardCharsets.UTF_8)).text();
            else if (isImage(lower)) text = ocrImage(bytes, lower);
            else if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
                    || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".log"))
                text = new String(bytes, StandardCharsets.UTF_8);
            else throw new BusinessException(400,
                    "不支持的格式（支持 PDF/DOCX/XLSX/XLS/PPTX/HTML/TXT/MD/CSV/JSON/PNG/JPG）：" + fileName);
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
                table.getRows().forEach(row -> {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String t = cell.getText();
                        cells.add(t == null ? "" : t.trim());
                    }
                    sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                });
            }
        }
        return sb.toString();
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String parseExcel(byte[] bytes, boolean xls) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = xls ? new HSSFWorkbook(new ByteArrayInputStream(bytes))
                : new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                sb.append("## ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(formatter.formatCellValue(cell).trim());
                    }
                    if (cells.stream().anyMatch(c -> !c.isBlank())) {
                        sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                    }
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
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) sb.append(text).append('\n');
                    } else if (shape instanceof XSLFTable table) {
                        for (XSLFTableRow row : table.getRows()) {
                            List<String> cells = new ArrayList<>();
                            for (XSLFTableCell cell : row.getCells()) {
                                cells.add(cell.getText() == null ? "" : cell.getText().trim());
                            }
                            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                        }
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
                BufferedImage image = renderer.renderImageWithDPI(i, Math.max(72, ocrDpi));
                Path imagePath = tempDir.resolve("page-" + (i + 1) + ".png");
                ImageIO.write(image, "png", imagePath.toFile());
                sb.append("\n## Page ").append(i + 1).append('\n')
                        .append(runOcr(imagePath)).append('\n');
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

    /** 调用外部 OCR 命令：tesseract <image> stdout -l <langs>。 */
    private String runOcr(Path image) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ocrCommand);
        command.add(image.toAbsolutePath().toString());
        command.add("stdout");
        if (ocrLanguages != null && !ocrLanguages.isBlank()) {
            command.add("-l");
            command.add(ocrLanguages);
        }
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
