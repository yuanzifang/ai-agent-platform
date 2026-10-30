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
}
