package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实多模态案例：需本机 Ollama 已加载 qwen3-vl:32b。
 * 默认跳过；运行：VISION_TEST_ENABLED=true mvn -Dtest=PdfVisionCaseTest test
 */
class PdfVisionCaseTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void realVisionDescribesPdfChartAndPhoto() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("VISION_TEST_ENABLED")),
                "设置 VISION_TEST_ENABLED=true 才调用真实视觉模型");
        ReflectionTestUtils.setField(parser, "vectorChartRenderEnabled", true);

        ImageUnderstandingService vision = new ImageUnderstandingService(new ObjectMapper());
        ReflectionTestUtils.setField(vision, "enabled", true);
        ReflectionTestUtils.setField(vision, "endpoint", "http://127.0.0.1:11434");
        ReflectionTestUtils.setField(vision, "model", "qwen3-vl:32b");
        ReflectionTestUtils.setField(vision, "timeoutSeconds", 600);
        ReflectionTestUtils.setField(vision, "maxImages", 5);

        List<DocumentParser.ExtractedImage> chartPhoto =
                parser.extractImages("chart-photo.pdf", chartPhotoPdf());
        assertTrue(chartPhoto.size() >= 2, "应提取图表和照片两张图");
        List<DocumentParser.ExtractedImage> vector =
                parser.extractImages("vector-chart.pdf", vectorChartPdf());
        assertTrue(vector.size() >= 1, "矢量图表应渲染为页面图片");

        List<DocumentParser.ExtractedImage> cases = List.of(
                chartPhoto.get(0), chartPhoto.get(1), vector.get(0));
        for (int i = 0; i < cases.size(); i++) {
            String description = vision.describe(cases.get(i).data(), cases.get(i).extension());
            System.out.println("[VISION-CASE " + (i + 1) + "] " + description);
            assertFalse(description.isBlank(), "真实视觉模型应返回描述，case=" + (i + 1));
        }
    }

    private byte[] chartPhotoPdf() throws Exception {
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
        BufferedImage photo = new BufferedImage(640, 426, BufferedImage.TYPE_INT_RGB);
        Graphics2D photoG = photo.createGraphics();
        try {
            photoG.setPaint(new java.awt.GradientPaint(0, 0, new Color(96, 170, 230),
                    0, 260, new Color(220, 242, 255)));
            photoG.fillRect(0, 0, 640, 426);
            photoG.setColor(new Color(255, 220, 120));
            photoG.fillOval(500, 55, 70, 70);
            photoG.setColor(new Color(92, 110, 92));
            photoG.fillPolygon(new int[]{0, 170, 300, 420, 640}, new int[]{260, 90, 180, 70, 250}, 5);
            photoG.setColor(new Color(54, 75, 56));
            photoG.fillPolygon(new int[]{0, 130, 260, 400, 640}, new int[]{300, 160, 250, 130, 310}, 5);
            photoG.setPaint(new java.awt.GradientPaint(0, 250, new Color(54, 120, 170),
                    0, 426, new Color(15, 45, 70)));
            photoG.fillRect(0, 250, 640, 176);
            photoG.setColor(new Color(30, 60, 40));
            for (int i = 0; i < 8; i++) {
                int x = 40 + i * 75;
                int h = 40 + (i % 3) * 18;
                photoG.fillPolygon(new int[]{x, x + 15, x + 30},
                        new int[]{330, 330 - h, 330}, 3);
            }
        } finally {
            photoG.dispose();
        }
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
            return out.toByteArray();
        }
    }

    private byte[] vectorChartPdf() throws Exception {
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
            return out.toByteArray();
        }
    }
}
