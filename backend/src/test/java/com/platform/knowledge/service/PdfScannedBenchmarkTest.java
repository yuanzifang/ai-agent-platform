package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描页 OCR 装配基准：默认使用空白页 + 确定性 OCR 回调，
 * 验证页面 OCR、分页锚点和正文装配；不依赖外部样本或 tesseract，默认不跳过。
 */
class PdfScannedBenchmarkTest {

    @Test
    void blankPdfPagesRunThroughPageOcrAssembly() throws Exception {
        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 3,
                false, "", "", 3);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) doc.addPage(new PDPage(PDRectangle.A4));
            PdfMarkdownConverter converter = new PdfMarkdownConverter(opts,
                    (document, page, options) -> new PdfMarkdownConverter.OcrResult(
                            "HISTOCHEMICAL CARCINOGENESIS LIVER " + new String[]{"ALPHA", "BETA", "GAMMA"}[page], null),
                    warning -> { }, 1);

            String text = converter.convert(doc);

            assertTrue(text.contains("HISTOCHEMICAL"), "应装配 OCR 正文，实际=" + text);
            assertTrue(text.contains("<!-- page: 1 -->")
                            && text.contains("<!-- page: 2 -->")
                            && text.contains("<!-- page: 3 -->"),
                    "应保留 3 页锚点，实际=" + text);
            assertTrue(converter.ocrPages().size() == 3, "应覆盖 3 页 OCR");
        }
    }
}
