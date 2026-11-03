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


class DocumentParserPdfOcrTableTest extends DocumentParserTestBase {

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
}
