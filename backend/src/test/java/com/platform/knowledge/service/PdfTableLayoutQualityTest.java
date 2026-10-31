package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTableLayoutQualityTest {

    @Test
    void dropsTableOfContentsAndSignatureBlocks() {
        assertFalse(PdfTableLayout.isUsefulBlock(block("""
                <table><caption>Table of Contents</caption>
                <tr><td>Item 1</td><td>Business</td><td>1</td></tr>
                <tr><td>Item 2</td><td>Risk</td><td>5</td></tr>
                <tr><td>Item 3</td><td>Financials</td><td>9</td></tr>
                </table>""")));
        assertFalse(PdfTableLayout.isUsefulBlock(block("""
                <table>
                <tr><td>Date</td><td>/S/</td><td>Name</td><td>Title</td></tr>
                <tr><td>2024</td><td>/S/</td><td>A</td><td>Director</td></tr>
                <tr><td>2024</td><td>/S/</td><td>B</td><td>Director</td></tr>
                <tr><td>2024</td><td>/S/</td><td>C</td><td>Director</td></tr>
                </table>""")));
    }

    @Test
    void keepsRealFinancialTable() {
        assertTrue(PdfTableLayout.isUsefulBlock(block("""
                <table>
                <thead><tr><th>Metric</th><th>2024</th><th>2023</th></tr></thead>
                <tbody>
                <tr><td>Revenue</td><td>120</td><td>100</td></tr>
                <tr><td>Profit</td><td>30</td><td>20</td></tr>
                <tr><td>Margin</td><td>25%</td><td>20%</td></tr>
                </tbody></table>""")));
    }

    @Test
    void dropsLongProseFramedAsTable() {
        String row = "<tr><td>" + "这是一段被错误框成表格的长正文，包含多个说明性词语和完整句子。".repeat(3)
                + "</td><td>" + "第二列同样是连续叙述性文本，而不是短表格单元格。".repeat(2) + "</td></tr>";
        assertFalse(PdfTableLayout.isUsefulBlock(block("<table>" + row.repeat(4) + "</table>")));
    }

    private static PdfTableBlock block(String html) {
        return PdfTableBlock.singlePage(0, 0, 100, 0, 500, html, false);
    }
    @Test
    void promotesLabelRowBeforeNumericDataToHeader() {
        PdfTableBlock raw = block("""
                <table>
                <tr><td>Exhibit</td><td>Period End</td><td>Exhibit Description</td></tr>
                <tr><td>101</td><td>2024</td><td>Inline XBRL</td></tr>
                <tr><td>102</td><td>2024</td><td>Cover page</td></tr>
                </table>""");

        PdfTableBlock promoted = PdfTableLayout.promoteHeader(raw);

        assertTrue(promoted.html().contains("<thead>"), promoted.html());
        assertTrue(promoted.html().contains("<th>Exhibit</th>"), promoted.html());
    }

    @Test
    void promotesTwoRowHeaderBeforeNumericData() {
        PdfTableBlock raw = block("""
                <table>
                <tr><td>Exhibit</td><td></td><td></td><td></td><td></td><td></td><td>Period End</td></tr>
                <tr><td>Number</td><td></td><td>Exhibit Description</td><td></td><td>Form</td><td>Exhibit</td><td>Date</td></tr>
                <tr><td>101</td><td></td><td>Inline XBRL</td><td></td><td>10-K</td><td>101</td><td>2024</td></tr>
                </table>""");

        PdfTableBlock promoted = PdfTableLayout.promoteHeader(raw);

        assertTrue(promoted.html().contains("<thead>"), promoted.html());
        assertTrue(promoted.html().contains("<th>Exhibit</th>"), promoted.html());
        assertTrue(promoted.html().contains("<th>Number</th>"), promoted.html());
    }

}
