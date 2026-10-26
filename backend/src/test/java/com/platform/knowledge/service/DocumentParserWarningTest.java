package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserWarningTest {

    @Test
    void ocrMaxPagesProducesWarningInsteadOfSilentTruncation() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过 OCR 告警测试");
        byte[] pdf = twoPageTextImagePdf();
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 1,
                false, "", "", 3);
        DocumentParser.ParseOutcome outcome = new DocumentParser()
                .parseWithParentsAndWarnings("scan-two-pages.pdf", pdf, opts);
        assertTrue(outcome.warnings().stream().anyMatch(w -> w.contains("扫描页 OCR 仅覆盖前 1 页")),
                "超过 OCR 上限应写入解析告警，实际=" + outcome.warnings());
        assertTrue(outcome.chunks().stream().anyMatch(c -> c.content().contains("SCAN")),
                "第一页 OCR 文本应保留");
    }

    @Test
    void parallelPageOcrWorks() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过页面并发 OCR 测试");
        DocumentParser parser = new DocumentParser();
        org.springframework.test.util.ReflectionTestUtils.setField(parser, "ocrConcurrency", 2);
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 2,
                false, "", "", 3);
        DocumentParser.ParseOutcome outcome = parser.parseWithParentsAndWarnings(
                "scan-page-parallel.pdf", twoPageTextImagePdf(), opts);
        String text = outcome.chunks().stream().map(DocumentParser.ParsedChunk::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.toUpperCase().contains("SCAN PAGE ONE")
                        && text.toUpperCase().contains("SCAN PAGE TWO"),
                "页面级并发 OCR 应识别两页文本，实际=" + text);
    }

    @Test
    void parallelEmbeddedImageOcrWorks() throws Exception {
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过并发 OCR 测试");
        DocumentParser parser = new DocumentParser();
        org.springframework.test.util.ReflectionTestUtils.setField(parser, "ocrConcurrency", 2);
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 1,
                false, "", "", 3);
        DocumentParser.ParseOutcome outcome = parser.parseWithParentsAndWarnings(
                "scan-parallel.pdf", twoPageTextImagePdf(), opts);
        String text = outcome.chunks().stream().map(DocumentParser.ParsedChunk::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.toUpperCase().contains("SCAN PAGE"),
                "内嵌图片并发 OCR 应识别出文本，实际=" + text);
    }

    @Test
    void sharedXObjectImageIsExtractedOnce() throws Exception {
        byte[] pdf;
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 200);
            g.setColor(Color.BLACK);
            g.drawString("SHARED LOGO", 50, 100);
        } finally {
            g.dispose();
        }
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDImageXObject xObject = LosslessFactory.createFromImage(doc, image);
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(xObject, 50, 400, 300, 150);
                }
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        List<DocumentParser.ExtractedImage> images = new DocumentParser()
                .extractImages("shared-logo.pdf", pdf);
        assertEquals(1, images.size(), "同一 XObject 在多页出现只应提取一次");
    }

    private static byte[] twoPageTextImagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String label : new String[]{"SCAN PAGE ONE", "SCAN PAGE TWO"}) {
                BufferedImage image = new BufferedImage(1000, 400, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, 1000, 400);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("SansSerif", Font.BOLD, 64));
                    g.drawString(label, 60, 220);
                } finally {
                    g.dispose();
                }
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(LosslessFactory.createFromImage(doc, image),
                            40, 400, 515, 206);
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean commandExists(String command) {
        try {
            return new ProcessBuilder(command, "--version").redirectErrorStream(true)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
