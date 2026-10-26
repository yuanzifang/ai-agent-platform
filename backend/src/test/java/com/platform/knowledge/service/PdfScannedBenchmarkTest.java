package com.platform.knowledge.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实扫描件 OCR 基准：默认跳过；设置 PDF_BENCH_DIR 且目录内存在
 * med-scanned-1960.pdf 时，把前 3 页渲染成纯图片 PDF（去掉文本层）后走 OCR 链路。
 */
class PdfScannedBenchmarkTest {

    @Test
    void scannedMedicalPdfOcr() throws Exception {
        String dir = System.getenv("PDF_BENCH_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "未设置 PDF_BENCH_DIR，跳过扫描件基准");
        Assumptions.assumeTrue(commandExists("tesseract"), "本机未安装 tesseract，跳过扫描件基准");
        Path source = Path.of(dir, "med-scanned-1960.pdf");
        Assumptions.assumeTrue(Files.exists(source), "缺少 med-scanned-1960.pdf，跳过扫描件基准");

        byte[] imageOnly = renderImageOnlyPdf(Files.readAllBytes(source), 3);
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 3,
                false, "", "", 3);
        long start = System.currentTimeMillis();
        List<DocumentParser.ParsedChunk> chunks = new DocumentParser()
                .parseWithParents("med-scanned-1960-image-only.pdf", imageOnly, opts);
        long ms = System.currentTimeMillis() - start;
        String text = chunks.stream().map(DocumentParser.ParsedChunk::content)
                .reduce("", (a, b) -> a + "\n" + b);
        int maxPage = chunks.stream().mapToInt(DocumentParser.ParsedChunk::pageTo).max().orElse(0);
        System.out.println("SCANNED-OCR pages=1-" + maxPage + " chunks=" + chunks.size()
                + " chars=" + text.length() + " ms=" + ms);
        System.out.println("SCANNED-PREVIEW>>>" + text.substring(0, Math.min(1500, text.length()))
                .replace("\n", "\\n"));
        assertTrue(maxPage == 3, "应覆盖 3 页扫描页");
        assertTrue(text.toUpperCase().contains("HISTOCHEMICAL")
                        || text.toUpperCase().contains("CARCINOGENESIS")
                        || text.toUpperCase().contains("LIVER"),
                "OCR 应识别出医学论文正文");
    }

    private byte[] renderImageOnlyPdf(byte[] source, int pages) throws Exception {
        try (PDDocument input = Loader.loadPDF(source);
             PDDocument output = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDFRenderer renderer = new PDFRenderer(input);
            for (int i = 0; i < Math.min(pages, input.getNumberOfPages()); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 150);
                PDPage page = new PDPage(PDRectangle.A4);
                output.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(output, page)) {
                    content.drawImage(LosslessFactory.createFromImage(output, image),
                            0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    private boolean commandExists(String command) {
        try {
            return new ProcessBuilder(command, "--version").redirectErrorStream(true)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
