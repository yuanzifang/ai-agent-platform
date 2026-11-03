package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.platform.knowledge.entity.KnowledgeOcrCache;
import com.platform.knowledge.mapper.KnowledgeOcrCacheMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

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
import org.apache.pdfbox.rendering.PDFRenderer;
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
import org.apache.poi.openxml4j.util.ZipSecureFile;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class DocumentParserPdfOcrTest extends DocumentParserTestBase {

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
    void usesPersistentOcrCacheWithoutInvokingTesseract() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeOcrCache.class);
        KnowledgeOcrCacheMapper cacheMapper = mock(KnowledgeOcrCacheMapper.class);
        KnowledgeOcrCache cache = new KnowledgeOcrCache();
        cache.setText("PERSISTED-OCR");
        cache.setTableHtml("");
        when(cacheMapper.selectOne(any())).thenReturn(cache);
        ReflectionTestUtils.setField(parser, "ocrCacheMapper", cacheMapper);
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);

        OcrTableExtractor.Result result = ReflectionTestUtils.invokeMethod(
                parser, "ocrBufferedImage", image, false, false, "test-");

        assertTrue(result != null && result.text().contains("PERSISTED-OCR"));
        verify(cacheMapper).selectOne(any());
    }

    @Test
    void computesOcrTextQualityForFallback() {
        assertEquals(1.0, (double) ReflectionTestUtils.invokeMethod(parser, "ocrQuality", "ABC123中文"));
        assertEquals(0.0, (double) ReflectionTestUtils.invokeMethod(parser, "ocrQuality", "###"));
    }

    @Test
    void appliesOtsuBinarizationWhenEnabled() throws Exception {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(100, 100, 100));
            g.fillRect(0, 0, 10, 10);
            g.setColor(new Color(180, 180, 180));
            g.fillRect(10, 0, 10, 10);
        } finally {
            g.dispose();
        }
        ReflectionTestUtils.setField(parser, "ocrPreprocess", true);
        ReflectionTestUtils.setField(parser, "ocrBinarize", true);

        BufferedImage processed = ReflectionTestUtils.invokeMethod(parser, "preprocessForOcr", image);

        for (int y = 0; y < processed.getHeight(); y++) {
            for (int x = 0; x < processed.getWidth(); x++) {
                int value = processed.getRaster().getSample(x, y, 0);
                assertTrue(value == 0 || value == 255, "二值化后像素应为 0/255，实际=" + value);
            }
        }
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

    @Test
    void ordersThreeColumnBodyLeftToRight() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                String[] cols = {"L", "M", "R"};
                float[] xs = {40, 240, 440};
                float y = 760;
                for (int line = 1; line <= 4; line++) {
                    for (int c = 0; c < cols.length; c++) {
                        content.beginText();
                        content.newLineAtOffset(xs[c], y);
                        content.showText(cols[c] + line + " text content");
                        content.endText();
                    }
                    y -= 20;
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("three-column.pdf", pdf));
        int l = text.indexOf("L1");
        int m = text.indexOf("M1");
        int r = text.indexOf("R1");
        assertTrue(l >= 0 && m > l && r > m, "三栏应按左中右排序，实际=" + text);
    }

    @Test
    void extractsTableAfterPageFifty() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int pageNo = 1; pageNo <= 51; pageNo++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.beginText();
                    content.newLineAtOffset(50, 760);
                    content.showText("Page " + pageNo);
                    content.endText();
                    if (pageNo == 51) {
                        content.beginText();
                        content.newLineAtOffset(50, 700);
                        content.showText("Product Price Qty");
                        content.endText();
                        content.beginText();
                        content.newLineAtOffset(50, 670);
                        content.showText("Row51 151 51");
                        content.endText();
                    }
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("large-pdf-with-late-table.pdf", pdf));
        assertTrue(text.contains("Row51") && text.contains("151"),
                "第 51 页表格应被提取，实际=" + text.substring(Math.max(0, text.length() - 500)));
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

        assertTrue(text.contains("<table") || text.contains("| C1"), "完整宽表应保留结构，实际=" + text);
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
}
