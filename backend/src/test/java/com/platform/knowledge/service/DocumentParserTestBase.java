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


abstract class DocumentParserTestBase {

    protected final DocumentParser parser = new DocumentParser();

    @TempDir
    protected Path tempDir;

    protected boolean commandExists(String command) {
        try {
            return new ProcessBuilder(command, "--version").redirectErrorStream(true)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    protected void addScannedTablePage(PDDocument doc, String[][] rows) throws Exception {
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

    protected static void drawDottedLine(PDPageContentStream content, float x1, float y1,
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

    protected static void drawGrid(PDPageContentStream content, float left, float bottom,
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

    protected static void drawRectTable(PDPageContentStream content, float left, float bottom,
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

    protected static void drawDashedLine(PDPageContentStream content, float x1, float y1,
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

    protected static boolean hasTable(String text) {
        return text.contains("<table") || text.contains("| --- |");
    }

    protected static void line(PDPageContentStream content, float x1, float y1,
                             float x2, float y2) throws Exception {
        content.moveTo(x1, y1);
        content.lineTo(x2, y2);
        content.stroke();
    }

    protected static int occurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }
}
