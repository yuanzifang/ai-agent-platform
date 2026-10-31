package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrTableExtractorSmallTableTest {

    @Test
    void structuresThreeRowTwoColumnTable() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "1\t1\t1\t1\t1\t1\t50\t10\t60\t12\t95\tName\n"
                + "1\t1\t1\t1\t1\t2\t220\t10\t80\t12\t95\tValue\n"
                + "1\t1\t1\t1\t2\t1\t50\t40\t60\t12\t95\tA\n"
                + "1\t1\t1\t1\t2\t2\t220\t40\t80\t12\t95\t1\n"
                + "1\t1\t1\t1\t3\t1\t50\t70\t60\t12\t95\tB\n"
                + "1\t1\t1\t1\t3\t2\t220\t70\t80\t12\t95\t2\n";

        OcrTableExtractor.Result result = OcrTableExtractor.extract(tsv);

        assertTrue(result.table().contains("<table"), result.table());
        assertTrue(result.table().contains("Name") && result.table().contains("Value"), result.table());
    }
    @Test
    void isolatesTableFromPrecedingProseLines() {
        StringBuilder tsv = new StringBuilder(
                "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n");
        // 上方三行双栏正文，与表格之间留出明显间距。
        for (int i = 0; i < 3; i++) {
            tsv.append("1\t1\t1\t1\t").append(i + 1).append("\t1\t50\t").append(10 + i * 20)
                    .append("\t60\t12\t95\tIntro").append((char) ('A' + i)).append("\n");
            tsv.append("1\t1\t1\t1\t").append(i + 1).append("\t2\t220\t").append(10 + i * 20)
                    .append("\t80\t12\t95\tparagraph").append((char) ('A' + i)).append("\n");
        }
        String[] rows = {"Name\tValue", "A\t1", "B\t2"};
        for (int i = 0; i < rows.length; i++) {
            int top = 200 + i * 30;
            String[] cells = rows[i].split("\t");
            tsv.append("1\t1\t1\t1\t").append(i + 1).append("\t1\t50\t").append(top)
                    .append("\t60\t12\t95\t").append(cells[0]).append("\n");
            tsv.append("1\t1\t1\t1\t").append(i + 1).append("\t2\t220\t").append(top)
                    .append("\t80\t12\t95\t").append(cells[1]).append("\n");
        }

        OcrTableExtractor.Result result = OcrTableExtractor.extract(tsv.toString());

        assertTrue(result.table().contains("<table"), result.table());
        assertTrue(result.table().contains("Name") && result.table().contains("Value"), result.table());
        assertTrue(!result.table().contains("Intro"), "正文行不应混入表格，实际=" + result.table());
    }

    @Test
    void usesWidestHeaderRowAsColumnAnchors() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "1\t1\t1\t1\t1\t1\t50\t10\t60\t12\t95\tName\n"
                + "1\t1\t1\t1\t1\t2\t220\t10\t60\t12\t95\tValue\n"
                + "1\t1\t1\t1\t1\t3\t400\t10\t60\t12\t95\tUnit\n"
                + "1\t1\t1\t1\t2\t1\t50\t40\t60\t12\t95\tA\n"
                + "1\t1\t1\t1\t2\t2\t230\t40\t60\t12\t95\t1\n"
                + "1\t1\t1\t1\t2\t3\t410\t40\t60\t12\t95\tkg\n"
                + "1\t1\t1\t1\t3\t1\t50\t70\t60\t12\t95\tB\n"
                + "1\t1\t1\t1\t3\t2\t230\t70\t60\t12\t95\t2\n"
                + "1\t1\t1\t1\t3\t3\t410\t70\t60\t12\t95\tkg\n";

        OcrTableExtractor.Result result = OcrTableExtractor.extract(tsv);

        assertTrue(result.table().contains("<table"), result.table());
        assertTrue(result.table().contains("<thead>") && result.table().contains("Name")
                && result.table().contains("Unit"), "表头应保留，实际=" + result.table());
        assertTrue(!result.table().contains("<td></td>"), "列锚点不应产生空列，实际=" + result.table());
    }

}
