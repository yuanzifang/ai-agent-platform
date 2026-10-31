package com.platform.knowledge.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PDF 样本基准：默认跳过，设置 PDF_BENCH_DIR 目录后运行。
 * 输出 target/pdf-bench-report.md，用于人工检查标题、表格、页码与耗时。
 */
class PdfBenchmarkTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,4}\\s+");
    private static final Pattern PIPE_TABLE = Pattern.compile("(?m)^\\|\\s*---");

    @Test
    void benchmark() throws Exception {
        String dir = System.getenv("PDF_BENCH_DIR");
        boolean external = dir != null && !dir.isBlank();
        List<Path> files;
        if (external) {
            try (var stream = Files.list(Path.of(dir))) {
                files = stream.filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        // 无文本层扫描件由 PdfScannedRealBenchmarkTest 单独走 OCR，避免把
                        // “扫描页没有文本层”误判成文本 PDF 回归失败。
                        .filter(path -> !path.getFileName().toString().startsWith("scanned-"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
        } else {
            Path fixture = Files.createTempFile("pdf-bench-", ".pdf");
            Files.write(fixture, fixturePdf());
            files = List.of(fixture);
        }
        assertTrue(!files.isEmpty(), "PDF 基准没有可解析样本");

        DocumentParser parser = new DocumentParser();
        StringBuilder report = new StringBuilder("# PDF 解析基准报告\n\n");
        report.append("| 文件 | 页数 | 大小 | 耗时 | 分段 | 字符 | 标题 | 表格 | 图片 | 页码覆盖 | 状态 |\n");
        report.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
        List<String> samples = new ArrayList<>();

        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            int pages = 0;
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                pages = doc.getNumberOfPages();
            } catch (Exception ignored) {
                // 页数读取失败时保留 0，解析结果会给出错误
            }
            List<DocumentParser.ParsedChunk> chunks = List.of();
            String error = null;
            long start = System.nanoTime();
            try {
                chunks = parser.parseWithParents(file.getFileName().toString(), bytes);
            } catch (Exception e) {
                error = e.getMessage();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = chunks.stream().map(DocumentParser.ParsedChunk::content).reduce("", (a, b) -> a + "\n" + b);
            int headings = count(HEADING.matcher(text));
            int tables = count(PIPE_TABLE.matcher(text)) + occurrences(text, "<table");
            int images = 0;
            try {
                images = parser.extractImages(file.getFileName().toString(), bytes).size();
            } catch (Exception ignored) {
                // 图片统计失败不影响解析结果
            }
            int maxPage = chunks.stream().mapToInt(DocumentParser.ParsedChunk::pageTo).max().orElse(0);
            String status = error == null ? "OK" : "FAIL: " + error;
            report.append("| ").append(file.getFileName()).append(" | ").append(pages)
                    .append(" | ").append(bytes.length / 1024).append("KB | ").append(ms)
                    .append("ms | ").append(chunks.size())
                    .append(" | ").append(text.length())
                    .append(" | ").append(headings)
                    .append(" | ").append(tables)
                    .append(" | ").append(images)
                    .append(" | 1-").append(maxPage)
                    .append(" | ").append(status).append(" |\n");
            assertTrue(error == null, "PDF 基准解析失败：" + file.getFileName() + "：" + error);
            assertTrue(!text.isBlank(), "PDF 基准正文为空：" + file.getFileName());
            assertTrue(maxPage >= pages, "PDF 页码覆盖不足：" + file.getFileName()
                    + "，期望至少 " + pages + " 页，实际 " + maxPage);
            assertSampleThresholds(file.getFileName().toString(), headings, tables);
            samples.add("\n## " + file.getFileName() + "\n\n```text\n"
                    + preview(text, 1200) + "\n```\n");
        }
        report.append(String.join("", samples));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/pdf-bench-report.md"), report.toString());
        System.out.println(report);
    }

    /** 真实样本的结构退化门禁；阈值保持宽松，只拦截“标题/表格整体消失”这类回归。 */
    private static void assertSampleThresholds(String name, int headings, int tables) {
        int minHeadings = 0;
        int minTables = 0;
        if (name.startsWith("2023ar")) {
            minHeadings = 3; minTables = 20;
        } else if (name.startsWith("apple-10k")) {
            minHeadings = 10; minTables = 4;
        } else if (name.startsWith("jpm-")) {
            minHeadings = 10; minTables = 1;
        } else if (name.startsWith("chem-2012.13202")) {
            minHeadings = 1;
        } else if (name.startsWith("chem-2610.") || name.startsWith("math-2012")) {
            minHeadings = 3;
        } else if (name.startsWith("nist-sp800")) {
            minHeadings = 50; minTables = 10;
        } else if (name.startsWith("scanned-nbs-catalog")) {
            minHeadings = 30; minTables = 5;
        } else if (name.startsWith("scanned-nbs-chem")) {
            minHeadings = 10; minTables = 1;
        }
        assertTrue(headings >= minHeadings, name + " 标题数量退化：期望至少 " + minHeadings
                + "，实际 " + headings);
        assertTrue(tables >= minTables, name + " 表格数量退化：期望至少 " + minTables
                + "，实际 " + tables);
    }

    private static byte[] fixturePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
                content.beginText();
                content.newLineAtOffset(50, 760);
                content.showText("Benchmark Report");
                content.endText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.beginText();
                content.newLineAtOffset(50, 720);
                content.showText("Product Price Quantity");
                content.newLineAtOffset(0, -24);
                content.showText("Water 1999 2");
                content.newLineAtOffset(0, -24);
                content.showText("Filter 99 10");
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int count(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static String preview(String text, int max) {
        String value = text.replace("\r", "");
        return value.length() <= max ? value : value.substring(0, max) + "\n...[truncated]";
    }
}
