package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfMarkdownConversionTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void recognizesLongFirstPagePaperTitle() throws Exception {
        String title = "Chemical Reactivity Studies by Natural Orbital Functional Methods "
                + "for Transition Metal Water Dehydrogenation";
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 17);
                text(content, 50, 790, title);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                text(content, 50, 700, "Abstract. This paper studies the reaction pathway.");
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = String.join("\n", parser.parse("paper.pdf", pdf));
        assertTrue(text.contains("# " + title), "首页长标题应识别为标题，实际=" + text);
    }

    @Test
    void mergesContinuationOfLongFirstPageTitle() throws Exception {
        String first = "Chemical Reactivity Studies by Natural Orbital Functional Methods for";
        String second = "Transition Metal Water Dehydrogenation";
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 17);
                text(content, 50, 790, first);
                text(content, 50, 762, second);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                text(content, 50, 700, "Abstract. This paper studies the reaction pathway.");
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = String.join("\n", parser.parse("paper.pdf", pdf));
        assertTrue(text.contains("# " + first + " " + second),
                "首页标题跨行应合并，实际=" + text);
    }

    @Test
    void convertsPdfToStructuredMarkdownWithInlineTableAndPages() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addPage(doc, 1, true);
            addPage(doc, 2, false);
            addPage(doc, 3, false);
            doc.save(out);
            pdf = out.toByteArray();
        }

        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("report.pdf", pdf);
        String text = chunks.stream().map(DocumentParser.ParsedChunk::content)
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(chunks.stream().anyMatch(c -> c.parentContent().contains("<!-- page: 1 -->")),
                "父块应保留页码锚点");
        assertTrue(text.contains("# Quarterly Report"), "20pt 标题应识别为 H1，实际=" + text);
        assertTrue(text.contains("## Revenue"), "14pt 小标题应识别为 H2，实际=" + text);
        assertTrue(text.contains("Revenue grew by 10%"), "正文应保留，实际=" + text);
        assertTrue(text.contains("| Product | Price | Qty |"), "简单表格应转 Markdown，实际=" + text);
        assertFalse(text.contains("CONFIDENTIAL"), "重复页眉应清理，实际=" + text);
        assertFalse(text.contains("Page 1"), "页码页脚应清理，实际=" + text);

        int before = text.indexOf("Revenue grew by 10%");
        int table = text.indexOf("| Product | Price | Qty |");
        int after = text.indexOf("After the table");
        assertTrue(before >= 0 && table > before && after > table,
                "表格应插回原位置而不是追加到文末，实际=" + text);

        assertTrue(chunks.stream().allMatch(c -> c.pageFrom() >= 1 && c.pageTo() >= c.pageFrom()),
                "PDF 分块应带页码，实际=" + chunks);
        assertTrue(chunks.stream().allMatch(c -> !c.embeddingContent().contains("<table")),
                "embedding 文本不应包含 HTML 标签");
        assertTrue(chunks.stream().anyMatch(c -> c.pageFrom() == 3), "应覆盖第 3 页");
    }

    private void addPage(PDDocument doc, int pageNumber, boolean withTable) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
            text(content, 50, 820, "CONFIDENTIAL");
            text(content, 50, 30, "Page " + pageNumber);

            if (pageNumber == 1) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 20);
                text(content, 50, 770, "Quarterly Report");
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                text(content, 50, 730, "Revenue");
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                text(content, 50, 700, "Revenue grew by 10% in Q1.");
                if (withTable) {
                    String[][] rows = {
                            {"Product", "Price", "Qty"},
                            {"Water", "1999", "2"},
                            {"Filter", "99", "10"}
                    };
                    float y = 650;
                    for (String[] row : rows) {
                        float[] xs = {50, 220, 360};
                        for (int i = 0; i < row.length; i++) text(content, xs[i], y, row[i]);
                        y -= 25;
                    }
                }
                text(content, 50, 520, "After the table, the report continues.");
            } else {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                text(content, 50, 700, "Page " + pageNumber + " body paragraph for retrieval.");
            }
        }
    }

    private static void text(PDPageContentStream content, float x, float y, String value) throws Exception {
        content.beginText();
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }
}
