package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不依赖外部样本的 PDF 解析快照：标题、表格、页码覆盖退化时 CI 直接失败。 */
class PdfSnapshotTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,4}\\s+");
    private static final Pattern PIPE_TABLE = Pattern.compile("(?m)^\\|\\s*---");

    @Test
    void parsesStructuredMultiPagePdfWithinThresholds() throws Exception {
        byte[] pdf = snapshotPdf();
        DocumentParser parser = new DocumentParser();
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("snapshot.pdf", pdf);
        String text = chunks.stream().map(DocumentParser.ParsedChunk::content)
.reduce("", (a, b) -> a + "\n" + b);

        int headings = count(HEADING.matcher(text));
        int tables = count(PIPE_TABLE.matcher(text)) + occurrences(text, "<table");
        int pageTo = chunks.stream().mapToInt(DocumentParser.ParsedChunk::pageTo).max().orElse(0);

        System.out.println("PDF SNAPSHOT headings=" + headings + " tables=" + tables + " pageTo=" + pageTo
                + "\n" + preview(text, 4000));
        assertTrue(chunks.size() >= 3, "多页文档应至少有多个分段");
        assertTrue(headings >= 3, "每页主标题应被识别，实际=" + headings);
        assertTrue(tables >= 1, "带网格线的表格应被识别，实际=" + tables);
        assertTrue(pageTo >= 3, "页码覆盖应包含最后一页，实际=" + pageTo);
        assertTrue(text.contains("PageThreeMarker"), "末页正文不能丢失");
    }

    private static byte[] snapshotPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font body = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage first = new PDPage(PDRectangle.A4);
            doc.addPage(first);
            try (PDPageContentStream c = new PDPageContentStream(doc, first)) {
                heading(c, bold, 18, 50, 780, "Annual Report");
                heading(c, bold, 14, 50, 745, "Financial Highlights");
                c.setLineWidth(0.8f);
                float x0 = 50, x1 = 200, x2 = 350, x3 = 500;
                float y0 = 700, y1 = 670, y2 = 640, y3 = 610;
                for (float y : new float[]{y0, y1, y2, y3}) {
                    c.moveTo(x0, y); c.lineTo(x3, y); c.stroke();
                }
                for (float x : new float[]{x0, x1, x2, x3}) {
                    c.moveTo(x, y0); c.lineTo(x, y3); c.stroke();
                }
                cell(c, body, x0, y1, "Metric");
                cell(c, body, x1, y1, "2025");
                cell(c, body, x2, y1, "2026");
                cell(c, body, x0, y2, "Revenue");
                cell(c, body, x1, y2, "100");
                cell(c, body, x2, y2, "120");
                cell(c, body, x0, y3, "Profit");
                cell(c, body, x1, y3, "20");
                cell(c, body, x2, y3, "30");
            }

            PDPage second = new PDPage(PDRectangle.A4);
            doc.addPage(second);
            try (PDPageContentStream c = new PDPageContentStream(doc, second)) {
                heading(c, bold, 18, 50, 780, "Risk Factors");
                paragraph(c, body, 50, 730, "Market risk may affect results in the coming year.");
                paragraph(c, body, 50, 700, "Liquidity risk is monitored by the treasury team.");
            }

            PDPage third = new PDPage(PDRectangle.A4);
            doc.addPage(third);
            try (PDPageContentStream c = new PDPageContentStream(doc, third)) {
                heading(c, bold, 18, 50, 780, "Appendix");
                paragraph(c, body, 50, 730, "PageThreeMarker contains the final page content.");
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void heading(PDPageContentStream c, PDType1Font font, float size,
                                float x, float y, String text) throws Exception {
        c.setFont(font, size);
        c.beginText();
        c.newLineAtOffset(x, y);
        c.showText(text);
        c.endText();
    }

    private static void paragraph(PDPageContentStream c, PDType1Font font, float x, float y,
                                  String text) throws Exception {
        c.setFont(font, 10);
        c.beginText();
        c.newLineAtOffset(x, y);
        c.showText(text);
        c.endText();
    }

    private static void cell(PDPageContentStream c, PDType1Font font, float x, float y,
                             String text) throws Exception {
        c.setFont(font, 10);
        c.beginText();
        c.newLineAtOffset(x + 5, y + 8);
        c.showText(text);
        c.endText();
    }

    private static int count(Matcher matcher) {
        int n = 0;
        while (matcher.find()) n++;
        return n;
    }

    private static int occurrences(String text, String value) {
        int n = 0, i = 0;
        while ((i = text.indexOf(value, i)) >= 0) { n++; i += value.length(); }
        return n;
    }

    private static String preview(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n...[truncated]";
    }
}
