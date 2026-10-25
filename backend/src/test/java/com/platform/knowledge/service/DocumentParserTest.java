package com.platform.knowledge.service;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import com.platform.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.util.Units;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesParentChildChunks() {
        String text = ("## 章节\n" + "这是一段用于验证父子分块的正文内容。".repeat(20) + "\n").repeat(8);
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("long.md",
                text.getBytes(StandardCharsets.UTF_8));
        assertTrue(chunks.size() > 4);
        assertTrue(chunks.stream().allMatch(c -> c.content().length() <= 500));
        assertTrue(chunks.stream().allMatch(c -> c.parentContent() != null && !c.parentContent().isBlank()));
        assertTrue(chunks.stream().map(DocumentParser.ParsedChunk::parentIndex).distinct().count() > 1);
    }

    @Test
    void ocrImageUsesConfiguredCommand() throws Exception {
        Path script = tempDir.resolve("fake-ocr.sh");
        Files.writeString(script, "#!/bin/sh\necho \"OCR识别文本\"\n", StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", script.toAbsolutePath().toString());
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("scan.png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        assertTrue(chunks.stream().anyMatch(c -> c.content().contains("OCR识别文本")));
    }

    @Test
    void realTesseractOcrOnRenderedImage() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过真实 OCR");
        BufferedImage image = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 64));
            g.drawString("INVOICE 1234.00", 50, 190);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("scan.png", out.toByteArray());
        String text = chunks.stream().map(DocumentParser.ParsedChunk::content).reduce("", String::concat);
        assertTrue(text.contains("1234"), "真实 OCR 应识别金额数字，实际=" + text);
    }

    private boolean commandExists(String command) {
        try {
            return new ProcessBuilder(command, "--version").redirectErrorStream(true)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void parsesXlsx() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("产品");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("名称");
            row.createCell(1).setCellValue("价格");
            var row2 = sheet.createRow(1);
            row2.createCell(0).setCellValue("企业版");
            row2.createCell(1).setCellValue("999");
            wb.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("price.xlsx", bytes));
        assertTrue(text.contains("企业版"));
        assertTrue(text.contains("999"));
    }

    @Test
    void ocrScannedPdfWithoutTextLayer() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描 PDF OCR");
        BufferedImage image = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 64));
            g.drawString("SCANNED 9876", 50, 190);
        } finally { g.dispose(); }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 400, 515, 140);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        String text = String.join("\n", parser.parse("scanned.pdf", pdf));
        assertTrue(text.contains("9876"), text);
    }

    @Test
    void parsesPdfTableAsHtml() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water purifier", "1999", "2"},
                    {"Filter", "", "10"},
                    {"Installation", "0", "1"}
            };
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                float y = 720;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("table.pdf", pdf));
        assertTrue(text.contains("<table"), "PDF 表格应输出 table 标记，实际=" + text);
        assertTrue(text.contains("<td>Water purifier</td>"), "应保留表格单元格文本，实际=" + text);
        assertTrue(text.contains("<td>1999</td>"), "应保留金额列，实际=" + text);
        assertTrue(text.contains("<td>Filter</td><td></td><td>10</td>"),
                "空单元格应保留列位，实际=" + text);
    }

    @Test
    void parsesPdfMultilineTableCell() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            String[][] rows = {
                    {"Long product name", "1999", "2"},
                    {"that wraps", "", ""},
                    {"Filter", "99", "10"},
                    {"Installation", "0", "1"}
            };
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                float y = 720;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 15;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("multiline.pdf", pdf));
        assertTrue(text.contains("<table"), "多行单元格 PDF 应输出表格，实际=" + text);
        assertTrue(text.contains("Long product name that wraps"),
                "换行单元格应合并到同一单元格，实际=" + text);
        assertTrue(text.split("<tr>", -1).length == 4, "应保留 3 个逻辑数据行 + 表头，实际=" + text);
    }

    @Test
    void infersPdfFirstColumnRowspan() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] rows = {
                        {"Category", "Item", "Price"},
                        {"Fruit", "Apple", "10"},
                        {"", "Pear", "12"},
                        {"Veg", "Kale", "5"},
                        {"", "Spinach", "6"}
                };
                float y = 720;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("rowspan.pdf", pdf));
        assertTrue(text.contains("<td rowspan=\"2\">Fruit</td>"),
                "首列分组应推断 rowspan，实际=" + text);
        assertTrue(text.contains("<td>Pear</td>") && text.contains("<td>Spinach</td>"),
                "被合并行的其他列应保留，实际=" + text);
    }

    @Test
    void infersPdfMiddleColumnRowspan() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] rows = {
                        {"Category", "Region", "Sales"},
                        {"A", "North", "10"},
                        {"A", "", "20"},
                        {"B", "South", "5"},
                        {"B", "", "7"}
                };
                float y = 720;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("rowspan-middle.pdf", pdf));
        assertTrue(text.contains("<td rowspan=\"2\">North</td>"),
                "中间列分组应推断 rowspan，实际=" + text);
        assertTrue(text.contains("<td rowspan=\"2\">South</td>"),
                "第二组中间列应推断 rowspan，实际=" + text);
        assertTrue(text.contains("<td>20</td>") && text.contains("<td>7</td>"),
                "被合并行的其他列应保留，实际=" + text);
    }

    @Test
    void infersPdfLongRowspan() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] rows = {
                        {"Category", "Item", "Price"},
                        {"Fruit", "Apple", "10"},
                        {"", "Pear", "12"},
                        {"", "Plum", "8"},
                        {"", "Fig", "9"},
                        {"Veg", "Kale", "5"}
                };
                float y = 720;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("rowspan-long.pdf", pdf));
        assertTrue(text.contains("<td rowspan=\"4\">Fruit</td>"),
                "4 行纵向分组应保留 rowspan，实际=" + text);
    }

    @Test
    void mergesPdfCrossPageShiftedColumns() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(doc, first)) {
                content.setFont(font, 12);
                String[][] rows = new String[10][];
                rows[0] = new String[]{"Product", "Price", "Qty"};
                for (int i = 1; i <= 9; i++) {
                    rows[i] = new String[]{"Row " + i, String.valueOf(100 + i), String.valueOf(i)};
                }
                float y = 780;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 60;
                }
            }
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(doc, second)) {
                content.setFont(font, 12);
                String[][] rows = {
                        {"Product", "Price", "Qty"},
                        {"Row 10", "110", "10"}
                };
                float y = 800;
                for (String[] row : rows) {
                    float[] xs = {80, 250, 390};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 40;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("cross-page-shifted.pdf", pdf));
        assertTrue(text.contains("Row 10"), "平移列续页不应丢失，实际=" + text);
        assertTrue(text.split("<table", -1).length == 2,
                "列位置平移但结构一致应合并为一个 table，实际=" + text);
    }

    @Test
    void parsesPdfYearHeaderRows() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] rows = {
                        {"Product", "Sales", "", ""},
                        {"", "2024", "2025", ""},
                        {"Water", "10", "20", ""},
                        {"Filter", "5", "5", ""},
                        {"Install", "0", "1", ""}
                };
                float y = 720;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360, 500};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("year-header.pdf", pdf));
        assertTrue(text.contains("<thead>"), "年份行应作为表头，实际=" + text);
        assertTrue(text.contains("<th>2024</th>") && text.contains("<th>2025</th>"),
                "年份表头应保留，实际=" + text);
    }

    @Test
    void mergesPdfCrossPageAddedColumn() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(doc, first)) {
                content.setFont(font, 12);
                String[][] rows = new String[10][];
                rows[0] = new String[]{"Product", "Price", "Qty"};
                for (int i = 1; i <= 9; i++) {
                    rows[i] = new String[]{"Row " + i, String.valueOf(100 + i), String.valueOf(i)};
                }
                float y = 780;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 60;
                }
            }
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(doc, second)) {
                content.setFont(font, 12);
                String[][] rows = {
                        {"Product", "Price", "Qty", "Discount"},
                        {"Row 10", "110", "10", "5"}
                };
                float y = 800;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360, 500};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 40;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("cross-page-added-column.pdf", pdf));
        assertTrue(text.contains("Row 10"), "新增列续页不应丢失，实际=" + text);
        assertTrue(text.contains("Discount"), "新增列表头应合并，实际=" + text);
        assertTrue(text.split("<table", -1).length == 2,
                "新增列续页应合并为一个 table，实际=" + text);
    }

    @Test
    void infersPdfVeryLongRowspan() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] rows = new String[14][];
                rows[0] = new String[]{"Category", "Item", "Price"};
                rows[1] = new String[]{"Fruit", "Apple", "10"};
                for (int i = 2; i <= 12; i++) {
                    rows[i] = new String[]{"", "Item " + i, String.valueOf(i)};
                }
                rows[13] = new String[]{"Veg", "Kale", "5"};
                float y = 760;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 45;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("rowspan-very-long.pdf", pdf));
        assertTrue(text.contains("<td rowspan=\"12\">Fruit</td>"),
                "12 行纵向分组应保留 rowspan，实际=" + text);
    }

    @Test
    void mergesPdfCrossPageReorderedColumns() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(doc, first)) {
                content.setFont(font, 12);
                String[][] rows = new String[10][];
                rows[0] = new String[]{"Product", "Price", "Qty"};
                for (int i = 1; i <= 9; i++) {
                    rows[i] = new String[]{"Row " + i, String.valueOf(100 + i), String.valueOf(i)};
                }
                float y = 780;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 60;
                }
            }
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(doc, second)) {
                content.setFont(font, 12);
                String[][] rows = {
                        {"Product", "Discount", "Qty"},
                        {"Row 10", "5", "10"}
                };
                float y = 800;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 40;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("cross-page-reordered.pdf", pdf));
        assertTrue(text.contains("<td>Row 10</td>"), "换序列续页不应丢失，实际=" + text);
        assertTrue(text.contains("Discount"), "新增/换序列表头应合并，实际=" + text);
        assertTrue(text.split("<table", -1).length == 2,
                "换序列续页应合并为一个 table，实际=" + text);
    }

    @Test
    void parsesPdfMultiLevelHeader() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                float[][] headers = {
                        {50, 220},
                        {220, 360}
                };
                String[][] headerRows = {
                        {"Product", "Sales"},
                        {"Q1", "Q2"}
                };
                float y = 720;
                for (int r = 0; r < headerRows.length; r++) {
                    for (int c = 0; c < headerRows[r].length; c++) {
                        content.beginText();
                        content.newLineAtOffset(headers[r][c], y);
                        content.showText(headerRows[r][c]);
                        content.endText();
                    }
                    y -= 30;
                }
                String[][] data = {
                        {"Water", "10", "20"},
                        {"Filter", "5", "5"},
                        {"Install", "0", "1"}
                };
                for (String[] row : data) {
                    float[] xs = {50, 220, 360};
                    for (int c = 0; c < row.length; c++) {
                        content.beginText();
                        content.newLineAtOffset(xs[c], y);
                        content.showText(row[c]);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("multi-header.pdf", pdf));
        assertTrue(text.contains("<thead>"), "多级表头应使用 thead，实际=" + text);
        assertTrue(text.contains("colspan=\"2\"") && text.contains("Sales"),
                "跨列表头应输出 colspan，实际=" + text);
        assertTrue(text.contains("<th>Q1</th>") && text.contains("<th>Q2</th>"),
                "第二级表头应保留，实际=" + text);
        assertTrue(text.contains("<td>Water</td>"), "数据行应保留，实际=" + text);
    }

    @Test
    void parsesPdfTableCaptionAndFootnote() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.beginText();
                content.newLineAtOffset(50, 720);
                content.showText("Table 1: Product prices");
                content.endText();
                String[][] rows = {
                        {"Product", "Price", "Qty"},
                        {"Water", "1999", "2"},
                        {"Filter", "99", "10"}
                };
                float y = 650;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 30;
                }
                content.beginText();
                content.newLineAtOffset(50, 520);
                content.showText("Note: prices include tax");
                content.endText();
                content.beginText();
                content.newLineAtOffset(50, 490);
                content.showText("Source: internal price list");
                content.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("table-caption.pdf", pdf));
        assertTrue(text.contains("<caption>Table 1: Product prices</caption>"), "应提取表格标题，实际=" + text);
        assertTrue(text.contains("table-note") && text.contains("Note: prices include tax"),
                "应提取表格脚注，实际=" + text);
        assertTrue(text.contains("Source: internal price list"),
                "多行脚注应全部保留，实际=" + text);
    }

    @Test
    void plainPdfDoesNotEmitTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[] lines = {
                        "This is a normal paragraph in a PDF document.",
                        "It has several lines but no tabular structure.",
                        "The parser must keep it as plain text."
                };
                float y = 720;
                for (String line : lines) {
                    content.beginText();
                    content.newLineAtOffset(50, y);
                    content.showText(line);
                    content.endText();
                    y -= 24;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("prose.pdf", pdf));
        assertTrue(text.contains("normal paragraph"));
        assertTrue(!text.contains("<table"), "普通正文不应被误判为表格，实际=" + text);
    }

    @Test
    void ocrScannedPdfTableAsHtml() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描表格 OCR");
        BufferedImage image = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 700);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"}
            };
            int[] xs = {100, 650, 1050};
            int y = 120;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    g.drawString(row[col], xs[col], y);
                }
                y += 120;
            }
            g.setColor(Color.DARK_GRAY);
            g.drawRect(70, 50, 1200, 480);
            for (int r = 1; r < 4; r++) g.drawLine(70, 50 + r * 120, 1270, 50 + r * 120);
            for (int x : new int[]{600, 1000}) g.drawLine(x, 50, x, 530);
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 300, 515, 258);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        String text = String.join("\n", parser.parse("scan-table.pdf", pdf));
        assertTrue(text.contains("<table"), "扫描表格应重建为 HTML，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
                "扫描表格单元格应保留，实际=" + text);
    }

    @Test
    void ocrRotatedScannedTable() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过旋转扫描 OCR");
        BufferedImage image = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 700);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"},
                    {"Service", "299", "5"},
                    {"Support", "199", "3"}
            };
            int[] xs = {100, 650, 1050};
            int y = 120;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    g.drawString(row[col], xs[col], y);
                }
                y += 90;
            }
        } finally {
            g.dispose();
        }
        BufferedImage rotated = new BufferedImage(700, 1400, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rotated.createGraphics();
        try {
            rg.setColor(Color.WHITE);
            rg.fillRect(0, 0, 700, 1400);
            rg.translate(0, 1400);
            rg.rotate(-Math.PI / 2);
            rg.drawImage(image, 0, 0, null);
        } finally {
            rg.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, rotated);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 180, 515, 515);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        ReflectionTestUtils.setField(parser, "ocrAutoOrient", true);
        String text = String.join("\n", parser.parse("rotated-table.pdf", pdf));
        assertTrue(text.contains("<table"), "旋转表格应可 OCR，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
                "旋转表格内容应保留，实际=" + text);
    }

    @Test
    void ocrSkewedScannedTable() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过倾斜扫描 OCR");
        BufferedImage image = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 700);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"},
                    {"Service", "299", "5"},
                    {"Support", "199", "3"}
            };
            int[] xs = {100, 650, 1050};
            int y = 120;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    g.drawString(row[col], xs[col], y);
                }
                y += 90;
            }
        } finally {
            g.dispose();
        }
        BufferedImage rotated = new BufferedImage(1700, 950, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rotated.createGraphics();
        try {
            rg.setColor(Color.WHITE);
            rg.fillRect(0, 0, 1700, 950);
            rg.rotate(Math.toRadians(6), 850, 475);
            rg.drawImage(image, 150, 120, null);
        } finally {
            rg.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, rotated);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 180, 515, 288);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        ReflectionTestUtils.setField(parser, "ocrAutoOrient", true);
        ReflectionTestUtils.setField(parser, "ocrDeskew", true);
        String text = String.join("\n", parser.parse("skew-table.pdf", pdf));
        assertTrue(text.contains("<table"), "倾斜表格应可 OCR，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
                "倾斜表格内容应保留，实际=" + text);
    }

    @Test
    void ocrNoisyScannedTable() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过去噪 OCR");
        BufferedImage image = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 700);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"}
            };
            int[] xs = {100, 650, 1050};
            int y = 120;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    g.drawString(row[col], xs[col], y);
                }
                y += 120;
            }
        } finally {
            g.dispose();
        }
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 1400 * 700 / 500; i++) {
            int x = random.nextInt(1400);
            int y = random.nextInt(700);
            image.setRGB(x, y, random.nextBoolean() ? 0x000000 : 0xFFFFFF);
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 300, 515, 258);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        ReflectionTestUtils.setField(parser, "ocrDenoise", true);
        String text = String.join("\n", parser.parse("noisy-table.pdf", pdf));
        assertTrue(text.contains("<table"), "噪点表格应可 OCR，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
                "噪点表格内容应保留，实际=" + text);
    }

    @Test
    void parseOptionsEnableOcrAndPreprocessTogether() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过 ParseOptions OCR");
        BufferedImage image = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 700);
            g.setColor(new Color(205, 205, 205));
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"}
            };
            int[] xs = {100, 650, 1050};
            int y = 120;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    g.drawString(row[col], xs[col], y);
                }
                y += 120;
            }
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 300, 515, 258);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 10,
                false, "", "", 3);
        String text = parser.parseWithParents("opts-table.pdf", pdf, opts).stream()
                .map(DocumentParser.ParsedChunk::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains("<table"), "ParseOptions 应启用 OCR/表格预处理，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
                "ParseOptions OCR 内容应保留，实际=" + text);
    }

    @Test
    void ocrScannedTableHeaderCaptionAndNote() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描表头 OCR");
        BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 900);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 40));
            g.drawString("Table 1: Product prices", 100, 60);
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"}
            };
            int[] xs = {100, 650, 1050};
            int y = 180;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) g.drawString(row[col], xs[col], y);
                y += 100;
            }
            g.drawString("Note: prices include tax", 100, 640);
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 240, 515, 331);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("scan-table-caption.pdf", pdf));
        assertTrue(text.contains("<caption>") && text.contains("Table 1"), "应提取扫描表格标题，实际=" + text);
        assertTrue(text.contains("<thead>") && text.contains("<th>Product</th>"),
                "扫描表格首行应为表头，实际=" + text);
        assertTrue(text.contains("table-note") && text.contains("Note:"),
                "扫描表格脚注应保留，实际=" + text);
    }

    @Test
    void ocrScannedMultiLevelHeaderColspan() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描多级表头");
        BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 900);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 42));
            g.drawString("Product", 100, 180);
            g.drawString("Sales", 650, 180);
            g.drawString("2024", 650, 280);
            g.drawString("2025", 1050, 280);
            String[][] rows = {
                    {"Water", "10", "20"},
                    {"Filter", "5", "5"},
                    {"Install", "0", "1"}
            };
            int y = 380;
            for (String[] row : rows) {
                int[] xs = {100, 650, 1050};
                for (int col = 0; col < row.length; col++) g.drawString(row[col], xs[col], y);
                y += 100;
            }
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 240, 515, 331);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("scan-table-multi-header.pdf", pdf));
        assertTrue(text.contains("<th colspan=\"2\">Sales</th>"),
                "扫描表头跨列应输出 colspan，实际=" + text);
        assertTrue(text.contains("<th>2024</th>") && text.contains("<th>2025</th>"),
                "年份表头应保留，实际=" + text);
    }

    @Test
    void ocrScannedTableRowspan() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描 rowspan");
        BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 900);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 42));
            String[][] rows = {
                    {"Category", "Item", "Price"},
                    {"Fruit", "Apple", "10"},
                    {"", "Pear", "12"},
                    {"Veg", "Kale", "5"},
                    {"", "Spinach", "6"}
            };
            int[] xs = {100, 650, 1050};
            int y = 180;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    if (!row[col].isEmpty()) g.drawString(row[col], xs[col], y);
                }
                y += 110;
            }
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 200, 515, 331);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("scan-table-rowspan.pdf", pdf));
        assertTrue(text.contains("<td rowspan=\"2\">Fruit</td>"),
                "扫描首列分组应输出 rowspan，实际=" + text);
        assertTrue(text.contains("<td>Pear</td>"), "被合并行其他列应保留，实际=" + text);
    }

    @Test
    void ocrScannedTwoColumnTable() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过两列扫描表格");
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1000, 800);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Item", "Price"},
                    {"Water", "1999"},
                    {"Filter", "99"},
                    {"Install", "0"},
                    {"Service", "299"}
            };
            int y = 140;
            for (String[] row : rows) {
                g.drawString(row[0], 100, y);
                g.drawString(row[1], 650, y);
                y += 110;
            }
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 260, 515, 412);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("scan-table-two-column.pdf", pdf));
        assertTrue(text.contains("<table") && text.contains("<th>Item</th>"),
                "两列扫描表格应输出表头，实际=" + text);
        assertTrue(text.contains("<td>Water</td>") && text.contains("<td>1999</td>"),
                "两列扫描表格内容应保留，实际=" + text);
    }

    @Test
    void ocrScannedTableMergesAcrossPages() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过跨页扫描表格");
        String[][] pageOne = {
                {"Product", "Price", "Qty"},
                {"Water", "1999", "2"},
                {"Filter", "99", "10"},
                {"Install", "0", "1"}
        };
        String[][] pageTwo = {
                {"Product", "Price", "Qty"},
                {"Service", "299", "5"},
                {"Support", "199", "3"},
                {"Return", "0", "1"}
        };
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addScannedTablePage(doc, pageOne);
            addScannedTablePage(doc, pageTwo);
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("scan-table-cross-page.pdf", pdf));
        assertTrue(text.contains("Service") && text.contains("Return"),
                "续页扫描表格行应保留，实际=" + text);
        assertTrue(text.split("<table", -1).length == 2,
                "同表头扫描表格应合并为一个 table，实际=" + text);
    }

    private void addScannedTablePage(PDDocument doc, String[][] rows) throws Exception {
        BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 900);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 42));
            int[] xs = {100, 650, 1050};
            int y = 160;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) g.drawString(row[col], xs[col], y);
                y += 120;
            }
        } finally {
            g.dispose();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        var pdImage = LosslessFactory.createFromImage(doc, image);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
            content.drawImage(pdImage, 40, 220, 515, 331);
        }
    }

    @Test
    void ocrScannedTableMergesChangedColumns() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描换列");
        String[][] pageOne = {
                {"Product", "Price", "Qty"},
                {"Water", "1999", "2"},
                {"Filter", "99", "10"},
                {"Install", "0", "1"}
        };
        String[][] pageTwo = {
                {"Product", "Qty", "Discount"},
                {"Service", "5", "299"},
                {"Support", "3", "199"},
                {"Return", "1", "0"}
        };
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addScannedTablePage(doc, pageOne);
            addScannedTablePage(doc, pageTwo);
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("scan-table-column-change.pdf", pdf));
        assertTrue(text.contains("Discount") && text.contains("Service"),
                "扫描换列续页应保留，实际=" + text);
        assertTrue(text.split("<table", -1).length == 2,
                "扫描换列应合并为一个 table，实际=" + text);
    }

    @Test
    void ocrLowContrastScannedTable() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过低对比度 OCR");
        BufferedImage image = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 700);
            g.setColor(new Color(205, 205, 205));
            g.setFont(new Font("SansSerif", Font.PLAIN, 46));
            String[][] rows = {
                    {"Product", "Price", "Qty"},
                    {"Water", "1999", "2"},
                    {"Filter", "99", "10"},
                    {"Install", "0", "1"}
            };
            int[] xs = {100, 650, 1050};
            int y = 120;
            for (String[] row : rows) {
                for (int col = 0; col < row.length; col++) {
                    g.drawString(row[col], xs[col], y);
                }
                y += 120;
            }
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 300, 515, 258);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        String text = String.join("\n", parser.parse("low-contrast-table.pdf", pdf));
        assertTrue(text.contains("<table"), "低对比度表格应可 OCR，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
                "低对比度表格内容应保留，实际=" + text);
    }

    @Test
    void ocrEmbeddedDocxImage() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过内嵌图片 OCR");
        BufferedImage image = new BufferedImage(900, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 56));
            g.drawString("IMAGE 5678", 40, 160);
        } finally { g.dispose(); }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var paragraph = doc.createParagraph();
            var run = paragraph.createRun();
            run.addPicture(new ByteArrayInputStream(png.toByteArray()), Document.PICTURE_TYPE_PNG,
                    "image.png", Units.toEMU(450), Units.toEMU(130));
            doc.write(out);
            bytes = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        String text = String.join("\n", parser.parse("image.docx", bytes));
        assertTrue(text.contains("5678"), text);
    }

    @Test
    void extractsPdfEmbeddedChartAndPhoto() throws Exception {
        BufferedImage chart = new BufferedImage(420, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D chartG = chart.createGraphics();
        try {
            chartG.setColor(Color.WHITE);
            chartG.fillRect(0, 0, 420, 240);
            chartG.setColor(Color.BLACK);
            chartG.drawLine(40, 200, 400, 200);
            chartG.drawLine(40, 200, 40, 30);
            chartG.setColor(new Color(30, 120, 220));
            chartG.fillRect(80, 120, 50, 80);
            chartG.fillRect(170, 80, 50, 120);
            chartG.fillRect(260, 50, 50, 150);
            chartG.setFont(new Font("SansSerif", Font.BOLD, 20));
            chartG.drawString("Quarterly Sales", 120, 25);
        } finally {
            chartG.dispose();
        }
        BufferedImage photo = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D photoG = photo.createGraphics();
        try {
            photoG.setColor(new Color(135, 206, 235));
            photoG.fillRect(0, 0, 320, 200);
            photoG.setColor(new Color(34, 139, 34));
            photoG.fillRect(0, 130, 320, 70);
            photoG.setColor(Color.WHITE);
            photoG.fillOval(220, 35, 50, 50);
        } finally {
            photoG.dispose();
        }

        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var chartImage = LosslessFactory.createFromImage(doc, chart);
            var photoImage = LosslessFactory.createFromImage(doc, photo);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(chartImage, 40, 520, 420, 240);
                content.drawImage(photoImage, 40, 220, 320, 200);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        List<DocumentParser.ExtractedImage> images = parser.extractImages("chart-photo.pdf", pdf);
        assertTrue(images.size() >= 2, "PDF 内嵌图表和照片都应被提取，实际=" + images.size());
        assertTrue(images.stream().anyMatch(image -> image.data().length > 1000));
    }

    @Test
    void rendersPdfVectorChartAsImage() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1.5f);
                content.setStrokingColor(Color.DARK_GRAY);
                for (int i = 0; i < 6; i++) {
                    content.moveTo(40, 600 - i * 30);
                    content.lineTo(500, 600 - i * 30);
                    content.stroke();
                }
                content.setNonStrokingColor(new Color(30, 120, 220));
                for (int i = 0; i < 3; i++) {
                    content.addRect(100 + i * 100, 420, 60, 80 + i * 20);
                    content.fill();
                }
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
                content.newLineAtOffset(80, 720);
                content.showText("Vector Sales Chart");
                content.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "vectorChartRenderEnabled", true);
        List<DocumentParser.ExtractedImage> images = parser.extractImages("vector-chart.pdf", pdf);
        assertTrue(images.size() >= 1, "矢量图表页应渲染为图片供多模态/OCR 使用");
        assertTrue(images.get(0).data().length > 1000);
        assertTrue((images.get(0).data()[0] & 0xFF) == 0x89, "渲染结果应为 PNG");
        String text = String.join("\n", parser.parse("vector-chart.pdf", pdf));
        assertTrue(text.contains("Vector Sales Chart"), "图表标签文本应保留，实际=" + text);
    }

    @Test
    void mergesPdfCrossPageTableContinuation() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(doc, first)) {
                content.setFont(font, 12);
                String[][] rows = new String[10][];
                rows[0] = new String[]{"Product", "Price", "Qty"};
                for (int i = 1; i <= 9; i++) {
                    rows[i] = new String[]{"Row " + i, String.valueOf(100 + i), String.valueOf(i)};
                }
                float y = 780;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 60;
                }
            }
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(doc, second)) {
                content.setFont(font, 12);
                String[][] rows = {
                        {"Product", "Price", "Qty"},
                        {"Row 10", "110", "10"}
                };
                float y = 800;
                for (String[] row : rows) {
                    float[] xs = {50, 220, 360};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 40;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("cross-page.pdf", pdf));
        assertTrue(text.contains("Row 9"), "第一页末行应存在，实际=" + text);
        assertTrue(text.contains("Row 10"), "续页少量行不应丢失，实际=" + text);
        assertTrue(text.split("<table", -1).length == 2,
                "跨页续表应合并为一个 table，实际=" + text);
    }

    @Test
    void parsesPdfFormsAndAnnotations() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(50, 760);
                content.showText("Customer form");
                content.endText();
            }
            PDAcroForm form = new PDAcroForm(doc);
            PDResources formResources = new PDResources();
            formResources.put(COSName.getPDFName("Helv"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(formResources);
            doc.getDocumentCatalog().setAcroForm(form);
            PDTextField field = new PDTextField(form);
            field.setPartialName("customer");
            field.setDefaultAppearance("/Helv 12 Tf 0 g");
            field.setValue("Alice");
            form.getFields().add(field);

            PDAnnotationText note = new PDAnnotationText();
            note.setContents("请核对金额");
            note.setRectangle(new PDRectangle(50, 600, 100, 30));
            page.getAnnotations().add(note);

            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("form.pdf", pdf));
        assertTrue(text.contains("[表单]customer: Alice"), "应提取 PDF 表单字段，实际=" + text);
        assertTrue(text.contains("[批注]") && text.contains("请核对金额"),
                "应提取 PDF 批注，实际=" + text);
    }

    @Test
    void rejectsEncryptedPdfWithClearMessage() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(50, 760);
                content.showText("encrypted document");
                content.endText();
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-password", "user-password", new AccessPermission());
            doc.protect(policy);
            doc.save(out);
            pdf = out.toByteArray();
        }
        BusinessException error = assertThrows(BusinessException.class,
                () -> parser.parse("encrypted.pdf", pdf));
        assertEquals(422, error.getCode());
        assertTrue(String.valueOf(error.getMessage()).contains("加密"));
    }

    @Test
    void extractsPdfInlineImage() throws Exception {
        COSDictionary params = new COSDictionary();
        params.setItem(COSName.W, COSInteger.get(4));
        params.setItem(COSName.H, COSInteger.get(4));
        params.setItem(COSName.CS, COSName.DEVICERGB);
        params.setItem(COSName.BPC, COSInteger.get(8));
        byte[] pixels = new byte[4 * 4 * 3];
        for (int i = 0; i < 16; i++) {
            pixels[i * 3] = (byte) (i * 15);
            pixels[i * 3 + 1] = (byte) 80;
            pixels[i * 3 + 2] = (byte) (255 - i * 10);
        }
        PDInlineImage inline = new PDInlineImage(params, pixels, new PDResources());
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(inline, 40, 600, 120, 120);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        List<DocumentParser.ExtractedImage> images = parser.extractImages("inline-image.pdf", pdf);
        assertTrue(images.size() >= 1, "PDF 内联图片应被提取");
        assertTrue((images.get(0).data()[0] & 0xFF) == 0x89, "内联图片应输出 PNG");
    }

    @Test
    void extractsPdfMixedPhotoAndVectorChart() throws Exception {
        BufferedImage photo = new BufferedImage(220, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        try {
            g.setColor(new Color(135, 206, 235));
            g.fillRect(0, 0, 220, 120);
            g.setColor(new Color(34, 139, 34));
            g.fillRect(0, 80, 220, 40);
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(LosslessFactory.createFromImage(doc, photo), 50, 620, 220, 120);
                content.setLineWidth(1.5f);
                for (int i = 0; i < 6; i++) {
                    content.moveTo(50, 500 - i * 30);
                    content.lineTo(500, 500 - i * 30);
                    content.stroke();
                }
                content.setNonStrokingColor(new Color(30, 120, 220));
                for (int i = 0; i < 3; i++) {
                    content.addRect(100 + i * 100, 330, 60, 70 + i * 20);
                    content.fill();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "vectorChartRenderEnabled", true);
        List<DocumentParser.ExtractedImage> images = parser.extractImages("mixed-photo-vector.pdf", pdf);
        assertTrue(images.size() >= 2,
                "混合页应同时保留内嵌照片并渲染矢量图表页，实际=" + images.size());
    }

    @Test
    void extractsDocxImages() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 200, 100);
        g.setColor(Color.BLACK); g.drawString("IMG", 20, 60); g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var run = doc.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(png.toByteArray()), Document.PICTURE_TYPE_PNG,
                    "image.png", Units.toEMU(200), Units.toEMU(100));
            doc.write(out);
            bytes = out.toByteArray();
        }
        List<DocumentParser.ExtractedImage> images = parser.extractImages("image.docx", bytes);
        assertTrue(images.size() >= 1, "应提取 DOCX 内嵌图片");
    }

    @Test
    void parsesDocxMergedCellsAsHtml() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("合并表头");
            table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewGridSpan()
                    .setVal(java.math.BigInteger.valueOf(2));
            doc.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("merged.docx", bytes));
        assertTrue(text.contains("<table"), text);
        assertTrue(text.contains("colspan=\"2\""), text);
        assertTrue(text.contains("合并表头"), text);
    }

    @Test
    void parsesLegacyDocWithTextutil() throws Exception {
        Assumptions.assumeTrue(commandExists("textutil"), "本机无 textutil，跳过 .doc 真实样本");
        Path txt = tempDir.resolve("legacy.txt");
        Path doc = tempDir.resolve("legacy.doc");
        Files.writeString(txt, "DOC格式测试：旧版Word内容。", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("textutil", "-convert", "doc",
                "-output", doc.toAbsolutePath().toString(), txt.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor() == 0, "textutil 生成 .doc 失败");
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("legacy.doc",
                Files.readAllBytes(doc));
        String text = chunks.stream().map(DocumentParser.ParsedChunk::content).reduce("", String::concat);
        assertTrue(text.contains("DOC格式测试"), text);
    }

    @Test
    void parsesPpt() throws Exception {
        byte[] bytes;
        try (HSLFSlideShow ppt = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HSLFSlide slide = ppt.createSlide();
            HSLFTextBox box = new HSLFTextBox();
            box.setText("旧版 PPT 内容");
            slide.addShape(box);
            ppt.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("old.ppt", bytes));
        assertTrue(text.contains("旧版 PPT 内容"), text);
    }

    @Test
    void parsesXlsxMergedCellsAsHtml() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("合并");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("合并表头");
            row.createCell(1).setCellValue("隐藏值");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            wb.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("merged.xlsx", bytes));
        assertTrue(text.contains("<table"), text);
        assertTrue(text.contains("colspan=\"2\""), text);
        assertTrue(text.contains("合并表头"), text);
    }

    @Test
    void parsesEpub() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/chapter1.xhtml"));
            zip.write("<html><body><h1>电子书标题</h1><p>EPUB 正文内容</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String text = String.join("\n", parser.parse("book.epub", out.toByteArray()));
        assertTrue(text.contains("电子书标题"), text);
        assertTrue(text.contains("EPUB 正文内容"), text);
    }

    @Test
    void parsesXlsxChartData() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("销量");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("月份");
            header.createCell(1).setCellValue("销量");
            String[] months = {"一月", "二月", "三月"};
            int[] values = {10, 20, 30};
            for (int i = 0; i < months.length; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(months[i]);
                row.createCell(1).setCellValue(values[i]);
            }
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFChart chart = drawing.createChart(new XSSFClientAnchor(0, 0, 0, 0, 3, 1, 10, 15));
            chart.setTitleText("月度销量");
            XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
            XDDFChartData data = chart.createData(ChartTypes.BAR, bottom, left);
            data.addSeries(XDDFDataSourcesFactory.fromStringCellRange(sheet, new CellRangeAddress(1, 3, 0, 0)),
                    XDDFDataSourcesFactory.fromNumericCellRange(sheet, new CellRangeAddress(1, 3, 1, 1)));
            chart.plot(data);
            workbook.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("chart.xlsx", bytes));
        assertTrue(text.contains("[图表]"), "应输出图表结构");
        assertTrue(text.contains("月度销量"), "应包含图表标题");
        assertTrue(text.contains("一月"), "应包含类目");
        assertTrue(text.contains("10"), "应包含数值");
    }

    @Test
    void parsesPptxTableMergedCellsAsHtml() throws Exception {
        byte[] bytes;
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTable table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("合并表头");
            table.getCell(0, 1).setText("B1");
            table.getCell(1, 0).setText("A2");
            table.getCell(1, 1).setText("B2");
            table.mergeCells(0, 0, 0, 1);
            ppt.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("table.pptx", bytes));
        assertTrue(text.contains("<table"), text);
        assertTrue(text.contains("colspan=\"2\""), text);
        assertTrue(text.contains("合并表头"), text);
    }

    @Test
    void parsesPptx() throws Exception {
        byte[] bytes;
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("季度目标：营收增长 30%");
            ppt.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("plan.pptx", bytes));
        assertTrue(text.contains("季度目标"));
        assertTrue(text.contains("30%"));
    }

    @Test
    void parsesHtmlAndCsv() {
        String html = String.join("\n", parser.parse("manual.html",
                "<html><body><h1>产品手册</h1><p>支持七天无理由退款</p></body></html>"
                        .getBytes(StandardCharsets.UTF_8)));
        assertTrue(html.contains("产品手册"));
        assertTrue(html.contains("七天无理由退款"));

        String csv = String.join("\n", parser.parse("data.csv",
                "姓名,城市\n张三,上海\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(csv.contains("张三"));
        assertTrue(csv.contains("上海"));
    }
}
