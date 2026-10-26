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
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import com.platform.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDXFAResource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void ocrCacheReusesSameImageAndOptions() throws Exception {
        Path counter = tempDir.resolve("ocr-cache-counter.txt");
        Path script = tempDir.resolve("fake-ocr-cache.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "echo call >> '" + counter.toAbsolutePath() + "'\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n'\n"
                + "printf '1\\t1\\t1\\t1\\t1\\t1\\t10\\t10\\t100\\t20\\t95\\tCACHED\\n'\n",
                StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", script.toAbsolutePath().toString());
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        BufferedImage image = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);

        OcrTableExtractor.Result first = ReflectionTestUtils.invokeMethod(
                parser, "ocrBufferedImage", image, false, false, "test-");
        OcrTableExtractor.Result second = ReflectionTestUtils.invokeMethod(
                parser, "ocrBufferedImage", image, false, false, "test-");

        assertTrue(first != null && first.text().contains("CACHED"));
        assertTrue(second != null && second.text().contains("CACHED"));
        assertEquals(1, Files.readAllLines(counter).size(), "相同图像应只调用一次 OCR");
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
    void keepsDenseUntitledWideTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                float y = 780;
                for (int row = 0; row <= 4; row++) {
                    for (int col = 1; col <= 13; col++) {
                        content.beginText();
                        content.newLineAtOffset(30 + (col - 1) * 42, y);
                        content.showText(row == 0 ? "C" + col : "R" + row + "C" + col);
                        content.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = String.join("\n", parser.parse("wide-untitled.pdf", pdf));

        assertTrue(text.contains("C13"), "13 列无标题表格应保留，实际=" + text);
        assertTrue(text.contains("R4C13"), "宽表数据行应保留，实际=" + text);
    }

    @Test
    void keepsSparseUntitledWideTableWithFullHeader() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                float y = 780;
                // 完整 13 列表头
                for (int col = 1; col <= 13; col++) {
                    content.beginText();
                    content.newLineAtOffset(30 + (col - 1) * 42, y);
                    content.showText("C" + col);
                    content.endText();
                }
                // 数据区故意稀疏，只填 5 列
                for (int row = 1; row <= 4; row++) {
                    y -= 30;
                    for (int col : new int[]{1, 4, 7, 10, 13}) {
                        content.beginText();
                        content.newLineAtOffset(30 + (col - 1) * 42, y);
                        content.showText("R" + row + "C" + col);
                        content.endText();
                    }
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = String.join("\n", parser.parse("wide-sparse.pdf", pdf));

        assertTrue(text.contains("C13"), "完整表头应保留，实际=" + text);
        assertTrue(text.contains("R4C13"), "稀疏数据行应保留，实际=" + text);
    }

    @Test
    void keepsLongRowSpanInAppendixTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 7);
                content.beginText();
                content.newLineAtOffset(50, 800);
                content.showText("Table 1. Appendix categories");
                content.endText();
                String[][] rows = new String[56][];
                rows[0] = new String[]{"Product", "Price", "Qty"};
                rows[1] = new String[]{"Fruit", "10", "1"};
                for (int i = 2; i < rows.length; i++) {
                    rows[i] = new String[]{"", String.valueOf(i), "1"};
                }
                float y = 770;
                for (String[] row : rows) {
                    float[] xs = {50, 240, 360};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 11;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("appendix-rowspan.pdf", pdf));
        assertTrue(text.contains("rowspan=\"55\"") && text.contains("Fruit"),
                "附录长纵向分组不应在 50 行处拆开，实际=" + text);
        assertTrue(text.contains("Product"), "长表表头应保留，实际=" + text);
    }

    @Test
    void doesNotTreatTextOnlySignatureListAsTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] rows = new String[13][];
                rows[0] = new String[]{"Name", "Date", "Signature"};
                for (int i = 1; i < rows.length; i++) {
                    rows[i] = new String[]{"Person " + i, "January", "Signed"};
                }
                float y = 780;
                for (String[] row : rows) {
                    float[] xs = {50, 240, 420};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 50;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = String.join("\n", parser.parse("signature-list.pdf", pdf));

        assertFalse(text.contains("<table"), "纯文本签名名单不应输出表格，实际=" + text);
        assertFalse(text.contains("| Name |"), "纯文本签名名单不应输出 Markdown 表，实际=" + text);
    }

    @Test
    void doesNotTreatPrismaFlowAsTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                String[][] rows = {
                        {"Identification", "Records identified", "1250"},
                        {"Screening", "Records screened", "980"},
                        {"Screening", "Records excluded", "270"},
                        {"Eligibility", "Full-text assessed", "120"},
                        {"Eligibility", "Full-text excluded", "80"},
                        {"Included", "Studies included", "40"},
                        {"Included", "Participants", "12500"}
                };
                float y = 780;
                for (String[] row : rows) {
                    float[] xs = {50, 230, 440};
                    for (int col = 0; col < row.length; col++) {
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 70;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = String.join("\n", parser.parse("prisma-flow.pdf", pdf));

        assertFalse(text.contains("<table"), "PRISMA 流程图不应输出表格，实际=" + text);
        assertFalse(text.contains("| Identification |"), "流程图不应输出 Markdown 表，实际=" + text);
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
    void parsesPdfTableAsMarkdown() throws Exception {
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
        assertTrue(text.contains("| Product | Price | Qty |"), "PDF 表格应输出 Markdown 表头，实际=" + text);
        assertTrue(text.contains("| Water purifier | 1999 | 2 |"), "应保留表格单元格文本，实际=" + text);
        assertTrue(text.contains("| Filter |  | 10 |"),
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
        assertTrue(hasTable(text), "多行单元格 PDF 应输出表格，实际=" + text);
        assertTrue(text.contains("| Long product name that wraps | 1999 | 2 |"),
                "换行单元格应合并到同一单元格，实际=" + text);
        assertTrue(text.contains("| Filter | 99 | 10 |") && text.contains("| Installation | 0 | 1 |"),
                "应保留 3 个逻辑数据行 + 表头，实际=" + text);
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
        assertTrue(text.contains("| Row 10 | 110 | 10 |"), "平移列续页应合并为 Markdown 表格，实际=" + text);
        assertEquals(1, occurrences(text, "| Product | Price | Qty |"), "重复表头应去重，实际=" + text);
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
    void parsesPdfFourLevelRectangleHeader() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                float[][] cells = {
                        {50, 540, 100, 160}, {150, 660, 400, 40},
                        {150, 620, 200, 40}, {350, 620, 200, 40},
                        {150, 580, 200, 40}, {350, 580, 200, 40},
                        {150, 540, 100, 40}, {250, 540, 100, 40},
                        {350, 540, 100, 40}, {450, 540, 100, 40},
                        {50, 500, 100, 40}, {150, 500, 100, 40},
                        {250, 500, 100, 40}, {350, 500, 100, 40}, {450, 500, 100, 40}
                };
                for (float[] cell : cells) {
                    content.addRect(cell[0], cell[1], cell[2], cell[3]);
                    content.stroke();
                }

                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                String[][] rows = {
                        {"Product", "Sales", "", "", ""},
                        {"", "2023", "", "2024", ""},
                        {"", "H1", "", "H2", ""},
                        {"", "Q1", "Q2", "Q3", "Q4"},
                        {"Water", "10", "20", "30", "40"}
                };
                float[] xs = {60, 160, 260, 360, 460};
                float y = 670;
                for (String[] row : rows) {
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
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
        String text = String.join("\n", parser.parse("four-level-rect.pdf", pdf));
        assertTrue(text.contains("<thead>"), "矩形四层表头应使用 thead，实际=" + text);
        assertTrue(text.contains("<th rowspan=\"4\">Product"), "首列应跨四行，实际=" + text);
        assertTrue(text.contains("<th colspan=\"4\">Sales"), "销售分组应跨四列，实际=" + text);
        assertTrue(text.contains("<th colspan=\"2\">2023") && text.contains("<th>Q1"),
                "年份和季度表头应保留，实际=" + text);
        assertTrue(text.contains("<td>Water"), "数据行不应并入表头，实际=" + text);
    }

    @Test
    void parsesPdfFourLevelLineHeader() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                line(content, 50, 500, 50, 700);
                line(content, 150, 500, 150, 700);
                line(content, 550, 500, 550, 700);
                line(content, 250, 500, 250, 580);
                line(content, 350, 500, 350, 660);
                line(content, 450, 500, 450, 580);
                line(content, 50, 700, 550, 700);
                line(content, 150, 660, 550, 660);
                line(content, 150, 620, 550, 620);
                line(content, 150, 580, 550, 580);
                line(content, 50, 540, 550, 540);
                line(content, 50, 500, 550, 500);

                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                String[][] rows = {
                        {"Product", "Sales", "", "", ""},
                        {"", "2023", "", "2024", ""},
                        {"", "H1", "", "H2", ""},
                        {"", "Q1", "Q2", "Q3", "Q4"},
                        {"Water", "10", "20", "30", "40"}
                };
                float[] xs = {60, 160, 260, 360, 460};
                float y = 670;
                for (String[] row : rows) {
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
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
        String text = String.join("\n", parser.parse("four-level-header.pdf", pdf));
        assertTrue(text.contains("<thead>"), "四层表头应使用 thead，实际=" + text);
        assertTrue(text.contains("<th rowspan=\"4\">Product"), "首列应跨四行，实际=" + text);
        assertTrue(text.contains("<th colspan=\"4\">Sales"), "销售分组应跨四列，实际=" + text);
        assertTrue(text.contains("<th colspan=\"2\">2023") && text.contains("<th>Q1"),
                "年份和季度表头应保留，实际=" + text);
        assertTrue(text.contains("<td>Water"), "数据行不应并入表头，实际=" + text);
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
        assertTrue(text.contains("| Row 10 | 110 |  | 5 |"), "新增列续页应合并，实际=" + text);
        assertEquals(1, occurrences(text, "| Product | Price | Qty | Discount |"),
                "重复表头应去重，实际=" + text);
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
        assertTrue(text.contains("Row 10"), "换序列续页不应丢失，实际=" + text);
        assertTrue(text.contains("Discount"), "新增/换序列表头应合并，实际=" + text);
        assertTrue(text.contains("| Row 10 |  | 10 | 5 |"), "换序列续页应合并，实际=" + text);
        assertEquals(1, occurrences(text, "| Product | Price | Qty | Discount |"),
                "重复表头应去重，实际=" + text);
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
        assertTrue(text.contains("**Table 1: Product prices**"), "应提取表格标题，实际=" + text);
        assertTrue(text.contains("> Note: prices include tax"),
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
        assertTrue(hasTable(text), "扫描表格应重建为表格，实际=" + text);
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
        assertTrue(hasTable(text), "旋转表格应可 OCR，实际=" + text);
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
        assertTrue(hasTable(text), "倾斜表格应可 OCR，实际=" + text);
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
        assertTrue(hasTable(text), "噪点表格应可 OCR，实际=" + text);
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
        assertTrue(hasTable(text), "ParseOptions 应启用 OCR/表格预处理，实际=" + text);
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
        assertTrue(text.contains("Table 1"), "应提取扫描表格标题，实际=" + text);
        assertTrue(text.contains("| Product |") || text.contains("<th>Product</th>"),
                "扫描表格首行应为表头，实际=" + text);
        assertTrue(text.contains("Note: prices include tax"),
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
    void ocrScannedGridTableRestoresSpans() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描网格合并");
        BufferedImage image = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 1400, 850);
            g.setColor(Color.DARK_GRAY);
            g.setStroke(new java.awt.BasicStroke(2f));
            int[] xs = {70, 550, 900, 1270};
            // 外框、表头分隔线；首列缺横线形成 Product/Fruit 的 rowspan
            for (int x : xs) {
                g.drawLine(x, x == 900 ? 230 : 100, x, 750);
            }
            g.drawLine(70, 100, 1270, 100);
            g.drawLine(550, 230, 1270, 230);
            g.drawLine(70, 360, 1270, 360);
            g.drawLine(70, 490, 1270, 490);
            g.drawLine(550, 620, 1270, 620);
            g.drawLine(70, 750, 1270, 750);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 32));
            g.drawString("Table 2: Sales", 100, 60);
            g.setFont(new Font("SansSerif", Font.PLAIN, 40));
            g.drawString("Product", 100, 190);
            g.drawString("Sales", 650, 190);
            g.drawString("2024", 600, 320);
            g.drawString("2025", 950, 320);
            g.drawString("Water", 100, 440);
            g.drawString("10", 600, 440);
            g.drawString("20", 950, 440);
            g.drawString("Fruit", 100, 580);
            g.drawString("Apple", 600, 580);
            g.drawString("5", 950, 580);
            g.drawString("Pear", 600, 710);
            g.drawString("6", 950, 710);
            g.setFont(new Font("SansSerif", Font.PLAIN, 32));
            g.drawString("Note: sample", 100, 810);
        } finally {
            g.dispose();
        }
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 40, 250, 515, 313);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", "tesseract");
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        String text = String.join("\n", parser.parse("scan-table-grid-spans.pdf", pdf));
        assertTrue(text.contains("<thead>") && text.contains("<th>2024</th>"),
                "扫描网格两级表头应输出 thead/th，实际=" + text);
        assertTrue(text.contains("colspan=\"2\"") && text.contains("Sales"),
                "扫描网格表头应输出 colspan，实际=" + text);
        assertTrue(text.contains("rowspan=\"2\"") && text.contains("Fruit"),
                "扫描网格数据应输出 rowspan，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("Apple"), "扫描网格文字应保留，实际=" + text);
        assertTrue(text.contains("<caption>") && text.contains("Table 2"), "扫描网格标题应保留，实际=" + text);
        assertTrue(text.contains("> Note: sample"), "扫描网格脚注应保留，实际=" + text);
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
        assertTrue(hasTable(text) && text.contains("Item"),
                "两列扫描表格应输出表头，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"),
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
        assertTrue(hasTable(text), "低对比度表格应可 OCR，实际=" + text);
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
    void deduplicatesCrossPageMultiRowHeader() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(doc, first)) {
                content.setFont(font, 12);
                String[][] header = {
                        {"Product", "Sales", "", "Discount"},
                        {"", "Price", "Qty", ""}
                };
                float y = 780;
                for (String[] row : header) {
                    float[] xs = {50, 180, 300, 420};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
                        content.beginText();
                        content.newLineAtOffset(xs[col], y);
                        content.showText(row[col]);
                        content.endText();
                    }
                    y -= 40;
                }
                for (int i = 1; i <= 9; i++) {
                    String[] row = {"Row " + i, String.valueOf(100 + i), String.valueOf(i), "5"};
                    float[] xs = {50, 180, 300, 420};
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
                        {"Product", "Sales", "", "Discount"},
                        {"", "Price", "Qty", ""},
                        {"Row 10", "110", "10", "5"}
                };
                float y = 800;
                for (String[] row : rows) {
                    float[] xs = {50, 180, 300, 420};
                    for (int col = 0; col < row.length; col++) {
                        if (row[col].isEmpty()) continue;
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
        String text = String.join("\n", parser.parse("cross-page-multi-header.pdf", pdf));
        assertEquals(1, occurrences(text, "Product"), "跨页多行表头应只保留一次，实际=" + text);
        assertTrue(text.contains("Row 10"), "续页数据行应保留，实际=" + text);
    }

    @Test
    void mergesCrossPageWithoutHeaderAndShiftedAnchors() throws Exception {
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
                        {"Row 10", "110", "10"},
                        {"Row 11", "111", "11"}
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
        String text = String.join("\n", parser.parse("cross-page-shifted-no-header.pdf", pdf));
        assertTrue(text.contains("| Row 10 | 110 | 10 |"), "无重复表头且列平移的续页应合并，实际=" + text);
        assertEquals(1, occurrences(text, "| Product | Price | Qty |"), "表头应只保留一次，实际=" + text);
    }

    @Test
    void mergesCrossPageSplitCellText() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(doc, first)) {
                content.setFont(font, 12);
                String[][] rows = new String[10][];
                rows[0] = new String[]{"Product", "Price", "Qty"};
                for (int i = 1; i <= 8; i++) {
                    rows[i] = new String[]{"Row " + i, String.valueOf(100 + i), String.valueOf(i)};
                }
                rows[9] = new String[]{"Long product", "1999", "2"};
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
                content.beginText();
                content.newLineAtOffset(50, 800);
                content.showText("that wraps");
                content.endText();
                content.beginText();
                content.newLineAtOffset(50, 760);
                content.showText("Row 10");
                content.endText();
                content.beginText();
                content.newLineAtOffset(220, 760);
                content.showText("110");
                content.endText();
                content.beginText();
                content.newLineAtOffset(360, 760);
                content.showText("10");
                content.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("cross-page-split-cell.pdf", pdf));
        assertTrue(text.contains("| Long product that wraps | 1999 | 2 |"),
                "跨页拆开的单元格应合并，实际=" + text);
        assertTrue(text.contains("| Row 10 | 110 | 10 |"), "续页数据行应保留，实际=" + text);
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
        assertTrue(text.contains("| Row 10 | 110 | 10 |"), "续页应并入同一 Markdown 表格，实际=" + text);
        assertEquals(1, occurrences(text, "| Product | Price | Qty |"), "跨页表头应只保留一次，实际=" + text);
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
    void parsesPdfOutlineAndLinks() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDDocumentOutline outline = new PDDocumentOutline();
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle("第一章");
            item.setDestination(page);
            outline.addLast(item);
            doc.getDocumentCatalog().setDocumentOutline(outline);

            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 700, 200, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/docs");
            link.setAction(uri);
            page.getAnnotations().add(link);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("outline.pdf", pdf));
        assertTrue(text.contains("[书签]第一章") && text.contains("第1页"),
                "应提取 PDF 书签及目标页码，实际=" + text);
        assertTrue(text.contains("[链接]https://example.com/docs"), "应提取 PDF 超链接，实际=" + text);
    }

    @Test
    void parsesPdfNamedDestinationLink() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage first = new PDPage(PDRectangle.A4);
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            doc.addPage(second);
            PDPageFitDestination target = new PDPageFitDestination();
            target.setPage(second);
            PDDestinationNameTreeNode dests = new PDDestinationNameTreeNode();
            dests.setNames(java.util.Map.of("chapter1", target));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setDests(dests);
            doc.getDocumentCatalog().setNames(names);

            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 700, 200, 20));
            PDActionGoTo goTo = new PDActionGoTo();
            goTo.setDestination(new PDNamedDestination("chapter1"));
            link.setAction(goTo);
            first.getAnnotations().add(link);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("named-dest.pdf", pdf));
        assertTrue(text.contains("[跳转]第2页"), "应解析命名目标跳转，实际=" + text);
    }

    @Test
    void parsesPdfEmbeddedAttachmentName() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("note.txt");
            spec.setEmbeddedFile(new PDEmbeddedFile(doc,
                    new ByteArrayInputStream("attachment text 123".getBytes(StandardCharsets.UTF_8))));
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(java.util.Map.of("note.txt", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("attachment.pdf", pdf));
        assertTrue(text.contains("[附件]note.txt"), "应提取 PDF 内嵌附件名称，实际=" + text);
        assertTrue(text.contains("[附件正文]note.txt") && text.contains("attachment text 123"),
                "应解析 PDF 内嵌附件正文，实际=" + text);
    }

    @Test
    void parsesPdfInternalLink() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage first = new PDPage(PDRectangle.A4);
            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            doc.addPage(second);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 700, 200, 20));
            PDActionGoTo goTo = new PDActionGoTo();
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(second);
            goTo.setDestination(destination);
            link.setAction(goTo);
            first.getAnnotations().add(link);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("internal-link.pdf", pdf));
        assertTrue(text.contains("[跳转]第2页"), "应提取 PDF 内部跳转，实际=" + text);
    }

    @Test
    void parsesPdfBorderedTableSpans() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                // header + 3 rows, Product/Sales header (Sales spans 2 cols), Fruit spans rows 2-3
                String[][] cells = {
                        {"Product", "50", "730", "150", "30"},
                        {"Sales", "200", "730", "220", "30"},
                        {"Water", "50", "700", "150", "30"},
                        {"10", "200", "700", "100", "30"},
                        {"20", "300", "700", "120", "30"},
                        {"Fruit", "50", "640", "150", "60"},
                        {"Apple", "200", "670", "100", "30"},
                        {"10x", "300", "670", "120", "30"},
                        {"Pear", "200", "640", "100", "30"},
                        {"12x", "300", "640", "120", "30"}
                };
                for (String[] c : cells) {
                    float x = Float.parseFloat(c[1]), y = Float.parseFloat(c[2]);
                    float w = Float.parseFloat(c[3]), h = Float.parseFloat(c[4]);
                    content.addRect(x, y, w, h);
                    content.stroke();
                    content.beginText();
                    content.newLineAtOffset(x + 5, y + 10);
                    content.showText(c[0]);
                    content.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("bordered-spans.pdf", pdf));
        assertTrue(text.contains("<thead>") && text.contains("<th>Product</th>"),
                "带边框表头应输出 thead/th，实际=" + text);
        assertTrue(text.contains("colspan=\"2\"") && text.contains("Sales"),
                "带边框表头应输出 colspan，实际=" + text);
        assertTrue(text.contains("rowspan=\"2\"") && text.contains("Fruit"),
                "带边框数据应输出 rowspan，实际=" + text);
    }

    @Test
    void parsesPdfLineGridTableSpans() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                float[] xs = {50, 200, 300, 420};
                for (int i = 0; i < 4; i++) {
                    float x = xs[i];
                    content.moveTo(x, 610);
                    content.lineTo(x, i == 0 || i == 1 || i == 3 ? 730 : 700);
                    content.stroke();
                }
                for (float y : new float[]{730, 700, 670, 610}) {
                    content.moveTo(50, y);
                    content.lineTo(420, y);
                    content.stroke();
                }
                // Fruit 首列跨两行：第 2/3 行之间首列不画分隔线
                content.moveTo(200, 640);
                content.lineTo(420, 640);
                content.stroke();

                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] texts = {
                        {"Table 3: Sales", "50", "760"},
                        {"Product", "60", "710"}, {"Sales", "220", "710"},
                        {"Water", "60", "680"}, {"10", "210", "680"}, {"20", "310", "680"},
                        {"Fruit", "60", "650"}, {"Apple", "210", "650"}, {"10x", "310", "650"},
                        {"Pear", "210", "620"}, {"12x", "310", "620"},
                        {"Note: sample", "50", "580"}
                };
                for (String[] t : texts) {
                    content.beginText();
                    content.newLineAtOffset(Float.parseFloat(t[1]), Float.parseFloat(t[2]));
                    content.showText(t[0]);
                    content.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("line-grid.pdf", pdf));
        assertTrue(text.contains("<thead>") && text.contains("<th>Product</th>")
                        && text.contains("colspan=\"2\"") && text.contains("Sales"),
                "线条表头应输出 thead、Product 与跨两列 Sales，实际=" + text);
        assertTrue(text.contains("rowspan=\"2\"") && text.contains("Fruit"),
                "线条数据应输出 rowspan，实际=" + text);
        assertTrue(text.contains("<caption>Table 3: Sales</caption>"),
                "线条表格应保留标题，实际=" + text);
        assertTrue(text.contains("> Note: sample"),
                "线条表格应保留脚注，实际=" + text);
    }

    @Test
    void mergesPlainGridTables() {
        String one = "<table border=\"1\">\n<tr><td>Product</td><td>Price</td></tr>"
                + "<tr><td>Water</td><td>1999</td></tr></table>";
        String two = "<table border=\"1\">\n<tr><td>Product</td><td>Price</td></tr>"
                + "<tr><td>Service</td><td>299</td></tr></table>";
        String merged = OcrTableExtractor.mergeTables(one, two);
        assertTrue(merged.split("<table", -1).length == 2, "应合并为一个表格，实际=" + merged);
        assertTrue(merged.contains("Water") && merged.contains("Service"), "应保留两页数据");
        assertTrue(merged.split("<td>Product</td>", -1).length == 2, "表头应只保留一次");
    }

    @Test
    void parsesPdfDottedGridTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                for (float x : new float[]{50, 200, 300}) drawDottedLine(content, x, 640, x, 730);
                for (float y : new float[]{730, 700, 670, 640}) drawDottedLine(content, 50, y, 300, y);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] texts = {{"Product", "60", "710"}, {"Price", "210", "710"},
                        {"Water", "60", "680"}, {"1999", "210", "680"},
                        {"Filter", "60", "650"}, {"99", "210", "650"}};
                for (String[] text : texts) {
                    content.beginText();
                    content.newLineAtOffset(Float.parseFloat(text[1]), Float.parseFloat(text[2]));
                    content.showText(text[0]);
                    content.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("dotted-grid.pdf", pdf));
        assertTrue(hasTable(text) && text.contains("| Product | Price |"),
                "点线表格应还原表头和表体，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"), "点线表格文字应保留，实际=" + text);
    }

    private static void drawDottedLine(PDPageContentStream content, float x1, float y1,
                                       float x2, float y2) throws Exception {
        double length = Math.hypot(x2 - x1, y2 - y1);
        double dx = (x2 - x1) / length;
        double dy = (y2 - y1) / length;
        for (double start = 0; start < length; start += 6) {
            double end = Math.min(start + 2, length);
            content.moveTo((float) (x1 + dx * start), (float) (y1 + dy * start));
            content.lineTo((float) (x1 + dx * end), (float) (y1 + dy * end));
            content.stroke();
        }
        // 补最后一个点，保证点线与相邻边框在端点相交。
        content.moveTo((float) (x1 + dx * Math.max(0, length - 2)), (float) (y1 + dy * Math.max(0, length - 2)));
        content.lineTo((float) x2, (float) y2);
        content.stroke();
    }

    @Test
    void parsesTwoLineGridTablesOnOnePage() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                drawGrid(content, 50, 700, 300, 770);
                drawGrid(content, 50, 500, 300, 580);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] texts = {{"Product", "60", "750"}, {"Price", "160", "750"},
                        {"Water", "60", "715"}, {"1999", "160", "715"},
                        {"Code", "60", "560"}, {"Qty", "160", "560"},
                        {"A-1", "60", "525"}, {"5", "160", "525"}};
                for (String[] text : texts) {
                    content.beginText();
                    content.newLineAtOffset(Float.parseFloat(text[1]), Float.parseFloat(text[2]));
                    content.showText(text[0]);
                    content.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("two-grids.pdf", pdf));
        assertTrue(hasTable(text), "同一页两个表格应分别输出，实际=" + text);
        assertTrue(text.contains("Product") && text.contains("Code"), "两个表头都应保留，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("A-1"), "两个表体都应保留，实际=" + text);
    }

    private static void drawGrid(PDPageContentStream content, float left, float bottom,
                                 float right, float top) throws Exception {
        for (float x : new float[]{left, (left + right) / 2, right}) {
            content.moveTo(x, bottom);
            content.lineTo(x, top);
            content.stroke();
        }
        for (float y : new float[]{bottom, (bottom + top) / 2, top}) {
            content.moveTo(left, y);
            content.lineTo(right, y);
            content.stroke();
        }
    }

    @Test
    void parsesTwoRectangleTablesOnOnePage() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                drawRectTable(content, 50, 700, 300, 770);
                drawRectTable(content, 50, 500, 300, 580);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] texts = {{"Product", "60", "750"}, {"Price", "185", "750"},
                        {"Water", "60", "715"}, {"1999", "185", "715"},
                        {"Code", "60", "560"}, {"Qty", "185", "560"},
                        {"A-1", "60", "525"}, {"5", "185", "525"}};
                for (String[] text : texts) {
                    content.beginText();
                    content.newLineAtOffset(Float.parseFloat(text[1]), Float.parseFloat(text[2]));
                    content.showText(text[0]);
                    content.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("two-rect-tables.pdf", pdf));
        assertTrue(hasTable(text), "同页两个矩形表格应分别输出，实际=" + text);
        assertTrue(text.contains("Product") && text.contains("Code"), "两个矩形表头都应保留，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("A-1"), "两个矩形表体都应保留，实际=" + text);
    }

    private static void drawRectTable(PDPageContentStream content, float left, float bottom,
                                      float right, float top) throws Exception {
        float midX = (left + right) / 2;
        float midY = (bottom + top) / 2;
        for (float y : new float[]{midY, top}) {
            for (float x : new float[]{left, midX}) {
                content.addRect(x, y == top ? midY : bottom, midX - left, midY - bottom);
                content.stroke();
            }
        }
    }

    @Test
    void parsesPdfDashedGridTable() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setLineWidth(1f);
                for (float x : new float[]{50, 200, 300}) drawDashedLine(content, x, 640, x, 730);
                for (float y : new float[]{730, 700, 670, 640}) drawDashedLine(content, 50, y, 300, y);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                String[][] texts = {{"Product", "60", "710"}, {"Price", "210", "710"},
                        {"Water", "60", "680"}, {"1999", "210", "680"},
                        {"Filter", "60", "650"}, {"99", "210", "650"}};
                for (String[] text : texts) {
                    content.beginText();
                    content.newLineAtOffset(Float.parseFloat(text[1]), Float.parseFloat(text[2]));
                    content.showText(text[0]);
                    content.endText();
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("dashed-grid.pdf", pdf));
        assertTrue(hasTable(text) && text.contains("| Product | Price |"),
                "虚线表格应还原表头和表体，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("1999"), "虚线表格文字应保留，实际=" + text);
    }

    private static void drawDashedLine(PDPageContentStream content, float x1, float y1,
                                       float x2, float y2) throws Exception {
        double length = Math.hypot(x2 - x1, y2 - y1);
        double dx = (x2 - x1) / length;
        double dy = (y2 - y1) / length;
        for (double start = 0; start < length; start += 12) {
            double end = Math.min(start + 7, length);
            content.moveTo((float) (x1 + dx * start), (float) (y1 + dy * start));
            content.lineTo((float) (x1 + dx * end), (float) (y1 + dy * end));
            content.stroke();
        }
    }

    @Test
    void mergesPdfGridTablesAcrossPages() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String[] row : new String[][]{{"Water", "1999"}, {"Service", "299"}}) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.setLineWidth(1f);
                    for (float x : new float[]{50, 200, 300}) {
                        content.moveTo(x, 670);
                        content.lineTo(x, 730);
                        content.stroke();
                    }
                    for (float y : new float[]{730, 700, 670}) {
                        content.moveTo(50, y);
                        content.lineTo(300, y);
                        content.stroke();
                    }
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    String[][] texts = {{"Product", "60", "710"}, {"Price", "210", "710"},
                            {row[0], "60", "680"}, {row[1], "210", "680"}};
                    for (String[] t : texts) {
                        content.beginText();
                        content.newLineAtOffset(Float.parseFloat(t[1]), Float.parseFloat(t[2]));
                        content.showText(t[0]);
                        content.endText();
                    }
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("grid-pages.pdf", pdf));
        assertTrue(text.contains("| Water | 1999 |") && text.contains("| Service | 299 |"),
                "跨页同表头应合并为一个表格，实际=" + text);
        assertTrue(text.contains("Water") && text.contains("Service"), "应保留两页数据");
        assertEquals(1, occurrences(text, "| Product | Price |"), "表头应只保留一次，实际=" + text);
    }

    @Test
    void mergesScannedColumnChangeWithMergedHeaders() {
        String one = "<table border=\"1\"><thead>"
                + "<tr><th rowspan=\"2\">Product</th><th colspan=\"2\">Sales</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr></thead><tbody>"
                + "<tr><td>Water</td><td>10</td><td>20</td></tr></tbody></table>";
        String two = "<table border=\"1\"><thead>"
                + "<tr><th rowspan=\"2\">Product</th><th colspan=\"2\">Sales</th>"
                + "<th rowspan=\"2\">Discount</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr></thead><tbody>"
                + "<tr><td>Service</td><td>5</td><td>6</td><td>10%</td></tr></tbody></table>";

        String merged = OcrTableExtractor.mergeTables(one, two);

        assertTrue(merged.split("<table", -1).length == 2, "应合并为一个表，实际=" + merged);
        assertTrue(merged.contains("Water") && merged.contains("Service"), "两页数据应保留");
        assertTrue(merged.contains("Discount"), "新增列应保留，实际=" + merged);
    }

    @Test
    void mergesColumnChangeWithColspanBody() {
        String one = "<table border=\"1\"><thead>"
                + "<tr><th rowspan=\"2\">Product</th><th colspan=\"2\">Sales</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr></thead><tbody>"
                + "<tr><td>Water</td><td>10</td><td>20</td></tr></tbody></table>";
        String two = "<table border=\"1\"><thead>"
                + "<tr><th rowspan=\"2\">Product</th><th colspan=\"2\">Sales</th>"
                + "<th rowspan=\"2\">Discount</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr></thead><tbody>"
                + "<tr><td colspan=\"2\">Service</td><td>5</td><td>10%</td></tr></tbody></table>";

        String merged = OcrTableExtractor.mergeTables(one, two);

        assertTrue(merged.split("<table", -1).length == 2, "应合并为一个表，实际=" + merged);
        assertTrue(merged.contains("Water") && merged.contains("Service"), "展开 colspan 后数据应保留");
        assertTrue(merged.contains("Discount"), "新增列应保留，实际=" + merged);
    }

    @Test
    void mergesColumnChangeWithRowspanBody() {
        String one = "<table border=\"1\"><thead>"
                + "<tr><th rowspan=\"2\">Product</th><th colspan=\"2\">Sales</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr></thead><tbody>"
                + "<tr><td rowspan=\"2\">Fruit</td><td>10</td><td>20</td></tr>"
                + "<tr><td>5</td><td>6</td></tr></tbody></table>";
        String two = "<table border=\"1\"><thead>"
                + "<tr><th rowspan=\"2\">Product</th><th colspan=\"2\">Sales</th>"
                + "<th rowspan=\"2\">Discount</th></tr>"
                + "<tr><th>2024</th><th>2025</th></tr></thead><tbody>"
                + "<tr><td rowspan=\"2\">Fruit</td><td>10</td><td>20</td><td>5</td></tr>"
                + "<tr><td>5</td><td>6</td><td>2</td></tr></tbody></table>";

        String merged = OcrTableExtractor.mergeTables(one, two);

        assertTrue(merged.split("<table", -1).length == 2, "应合并为一个表，实际=" + merged);
        assertTrue(merged.contains("Fruit") && merged.contains("5"), "rowspan 展开后数据应保留");
        assertTrue(merged.contains("Discount"), "新增列应保留，实际=" + merged);
    }

    @Test
    void parsesPdfMetadata() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            doc.getDocumentInformation().setTitle("产品手册");
            doc.getDocumentInformation().setAuthor("智云科技");
            doc.getDocumentInformation().setSubject("净水器");
            doc.getDocumentInformation().setKeywords("净水器,退款");
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("metadata.pdf", pdf));
        assertTrue(text.contains("[PDF元数据]") && text.contains("页数: 1"),
                "应提取 PDF 元数据页数，实际=" + text);
        assertTrue(text.contains("标题: 产品手册") && text.contains("作者: 智云科技"),
                "应提取 PDF 标题/作者，实际=" + text);
    }

    @Test
    void parsesPdfXfaForm() throws Exception {
        String xfa = "<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\">"
                + "<template><subform><field name=\"customer\"><value>Alice XFA</value></field>"
                + "</subform></template></xdp:xdp>";
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(50, 760);
                content.showText("XFA form");
                content.endText();
            }
            PDAcroForm form = new PDAcroForm(doc);
            COSStream stream = new COSStream();
            try (java.io.OutputStream os = stream.createOutputStream()) {
                os.write(xfa.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            form.setXFA(new PDXFAResource(stream));
            doc.getDocumentCatalog().setAcroForm(form);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("xfa.pdf", pdf));
        assertTrue(text.contains("[XFA]") && text.contains("Alice XFA"),
                "应提取 XFA 内容，实际=" + text);
    }

    @Test
    void parsesDynamicXfaDatasetPaths() throws Exception {
        String xfa = "<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\" xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\">"
                + "<xfa:datasets><xfa:data><form><customer>Bob</customer>"
                + "<items><item><name>A</name><qty>2</qty></item>"
                + "<item><name>B</name><qty>3</qty></item></items></form></xfa:data></xfa:datasets>"
                + "</xdp:xdp>";
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(50, 760);
                content.showText("Dynamic XFA form");
                content.endText();
            }
            PDAcroForm form = new PDAcroForm(doc);
            COSStream stream = new COSStream();
            try (java.io.OutputStream os = stream.createOutputStream()) {
                os.write(xfa.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            form.setXFA(new PDXFAResource(stream));
            doc.getDocumentCatalog().setAcroForm(form);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("dynamic-xfa.pdf", pdf));
        assertTrue(text.contains("[XFA数据]"), "应输出 XFA 数据区，实际=" + text);
        assertTrue(text.contains("form.customer: Bob"), "应提取动态字段路径，实际=" + text);
        assertTrue(text.contains("form.items.item[2].name: B"),
                "重复子节点应带序号，实际=" + text);
        assertTrue(text.contains("form.items.item.qty: 2") && text.contains("form.items.item[2].qty: 3"),
                "重复子节点值应保留，实际=" + text);
    }

    @Test
    void parsesDynamicXfaTemplateFields() throws Exception {
        String xfa = "<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\">"
                + "<template xmlns=\"http://www.xfa.org/schema/xfa-template/3.6/\">"
                + "<subform name=\"form\"><field name=\"customer\"><caption><value><text>Customer</text></value></caption></field>"
                + "</subform></template></xdp:xdp>";
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(50, 760);
                content.showText("Dynamic XFA template");
                content.endText();
            }
            PDAcroForm form = new PDAcroForm(doc);
            COSStream stream = new COSStream();
            try (java.io.OutputStream os = stream.createOutputStream()) {
                os.write(xfa.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            form.setXFA(new PDXFAResource(stream));
            doc.getDocumentCatalog().setAcroForm(form);
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("dynamic-xfa-template.pdf", pdf));
        assertTrue(text.contains("[XFA字段]") && text.contains("form.customer (Customer)"),
                "应提取 XFA 模板字段和标签，实际=" + text);
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
    void ocrTextProvidesChartEvidence() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过图表 OCR 证据");
        BufferedImage image = new BufferedImage(500, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 500, 160);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 56));
            g.drawString("1999", 60, 100);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 20,
                false, "", "", 3);

        String text = parser.ocrText(png.toByteArray(), "png", opts);

        assertTrue(text.contains("1999"), "图表 OCR 证据应包含数值，实际=" + text);
    }

    @Test
    void parsesEncryptedPdfWithPassword() throws Exception {
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
        ParseOptions opts = new ParseOptions(false, "tesseract", "chi_sim+eng", 150, 20,
                false, "", "", 3, "user-password");
        String text = parser.parseWithParents("encrypted.pdf", pdf, opts).stream()
                .map(DocumentParser.ParsedChunk::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains("encrypted document"), "提供正确密码后应能解析，实际=" + text);
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
        assertTrue(text.contains("[图表源校验]") && text.contains("差异 0"),
                "图表值应与源单元格逐点一致，实际=" + text);
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

    private static boolean hasTable(String text) {
        return text.contains("<table") || text.contains("| --- |");
    }

    private static void line(PDPageContentStream content, float x1, float y1,
                             float x2, float y2) throws Exception {
        content.moveTo(x1, y1);
        content.lineTo(x2, y2);
        content.stroke();
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
