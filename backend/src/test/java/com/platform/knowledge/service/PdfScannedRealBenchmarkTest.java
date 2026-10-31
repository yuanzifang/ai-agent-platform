package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实无文本层扫描件 OCR 回归；由 PDF_BENCH_DIR / nightly 提供样本。 */
class PdfScannedRealBenchmarkTest {

    @Test
    void ocrScannedEpaLetter() throws Exception {
        String dir = System.getenv("PDF_BENCH_DIR");
        if (dir == null || dir.isBlank()) return;
        Path file = Path.of(dir, "scanned-epa-letter-2015.pdf");
        if (!Files.exists(file)) return;

        ParseOptions opts = new ParseOptions(true, "tesseract", "eng", 150, 3,
                false, "", "", 3);
        long started = System.currentTimeMillis();
        List<DocumentParser.ParsedChunk> chunks = new DocumentParser()
                .parseWithParents(file.getFileName().toString(), Files.readAllBytes(file), opts);
        long ms = System.currentTimeMillis() - started;
        String text = chunks.stream().map(DocumentParser.ParsedChunk::content)
                .reduce("", (a, b) -> a + "\n" + b);
        int maxPage = chunks.stream().mapToInt(DocumentParser.ParsedChunk::pageTo).max().orElse(0);

        System.out.printf("SCANNED_REAL pages=1-%d chunks=%d chars=%d ms=%d%n",
                maxPage, chunks.size(), text.length(), ms);
        assertTrue(maxPage >= 3, "扫描件应覆盖 3 页，实际=" + maxPage);
        assertTrue(text.length() > 2000, "OCR 正文过短，实际=" + text.length());
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("environmental") || lower.contains("drinking"),
                "应识别出 EPA/饮用水正文，实际前 500 字=" + text.substring(0, Math.min(500, text.length())));
    }
}
