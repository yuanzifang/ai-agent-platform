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


class DocumentParserOfficeTest extends DocumentParserTestBase {

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
    void mergesCrossPageAddedAndReorderedHeaders() throws Exception {
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
                        {"Product", "Discount", "Qty", "Price"},
                        {"Row 10", "5", "10", "110"}
                };
                float y = 800;
                for (String[] row : rows) {
                    float[] xs = {50, 170, 300, 430};
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
        String text = String.join("\n", parser.parse("cross-page-add-reorder.pdf", pdf));
        assertEquals(1, occurrences(text, "Product"), "跨页表头应只保留一次，实际=" + text);
        assertTrue(text.contains("Row 10"), "续页数据应保留，实际=" + text);
        assertTrue(text.contains("Discount"), "新增列应保留，实际=" + text);
    }

    @Test
    void extendsRowSpanAcrossPageBoundary() throws Exception {
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
                rows[9] = new String[]{"Fruit", "10", "1"};
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
                float y = 800;
                for (String[] row : new String[][]{{"", "11", "2"}, {"", "12", "3"}}) {
                    float[] xs = {50, 220, 360};
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
        String text = String.join("\n", parser.parse("cross-page-rowspan.pdf", pdf));
        assertTrue(text.contains("rowspan=\"3\"") && text.contains("Fruit"),
                "跨页 rowspan 应延续，实际=" + text);
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
    void parsesLegacyDocFixture() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("/legacy.doc")) {
            assertTrue(in != null, "缺少 .doc 测试 fixture");
            bytes = in.readAllBytes();
        }
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("legacy.doc", bytes);
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

    @Test
    void limitsPdfPageRenderPixels() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A0));
            ReflectionTestUtils.setField(parser, "maxPdfRenderPixels", 1_000_000L);
            BufferedImage image = ReflectionTestUtils.invokeMethod(parser, "renderPdfPage",
                    new PDFRenderer(doc), doc, 0, 150);
            assertTrue((long) image.getWidth() * image.getHeight() <= 1_000_000L,
                    "超大一页必须在渲染前降 DPI，像素数不能超过上限");
        }
    }

    @Test
    void configuresExplicitZipBombLimits() {
        assertEquals(64L * 1024 * 1024, ZipSecureFile.getMaxEntrySize());
        assertEquals(0.01d, ZipSecureFile.getMinInflateRatio(), 0.0001d);
    }

    @Test
    void skipsOversizedEmbeddedImageBeforeDecoding() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 50, 500, 200, 200);
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        ReflectionTestUtils.setField(parser, "maxPdfRenderPixels", 1_000L);

        List<DocumentParser.ExtractedImage> images = parser.extractImages("oversized.pdf", pdf);

        assertTrue(images.isEmpty(), "超过像素上限的内嵌图应在解码前跳过，实际=" + images.size());
    }
}
