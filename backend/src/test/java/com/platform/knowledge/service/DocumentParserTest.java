package com.platform.knowledge.service;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
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
