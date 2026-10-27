package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownChunkerTest {

    @Test
    void keepsTableAtomic() {
        StringBuilder text = new StringBuilder("<!-- page: 1 -->\n\n## 销售明细\n\n");
        text.append("| Product | Price | Qty |\n| --- | --- | --- |\n");
        for (int i = 0; i < 40; i++) {
            text.append("| Item ").append(i).append(" | ").append(100 + i).append(" | ").append(i).append(" |\n");
        }
        text.append("\n表格后的正文。");

        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.split(text.toString());
        MarkdownChunker.Chunk table = chunks.stream()
                .filter(c -> c.content().startsWith("| Product"))
                .findFirst().orElseThrow();
        assertTrue(table.content().contains("| Item 39 | 139 | 39 |"), "表格不应被拆开");
        assertEquals(42, table.content().lines().count(), "表头 + 分隔行 + 40 行数据应保持完整");
        assertTrue(table.embeddingText().contains("销售明细"), "embedding 应带标题路径");
        assertFalse(table.embeddingText().contains("|"), "embedding 不应包含表格标记");
    }

    @Test
    void splitsByPageAndKeepsHeadingPath() {
        String text = "<!-- page: 1 -->\n\n# 年报\n\n## 财务\n\n营收增长 10%。\n\n"
                + "<!-- page: 2 -->\n\n## 风险\n\n供应链风险可控。";
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.split(text);
        assertTrue(chunks.stream().anyMatch(c -> c.pageFrom() == 1 && c.content().contains("营收增长")));
        assertTrue(chunks.stream().anyMatch(c -> c.pageFrom() == 2 && c.content().contains("供应链风险")));
        assertTrue(chunks.stream().anyMatch(c -> c.embeddingText().contains("财务")),
                "embedding 应补全所属标题");
        assertTrue(chunks.stream().allMatch(c -> c.parentContent().contains("<!-- page:")),
                "父块应保留页码锚点");
    }

    @Test
    void splitsHugePipeTableAndRepeatsHeader() {
        StringBuilder text = new StringBuilder("| Product | Price | Qty |\n| --- | --- | --- |\n");
        for (int i = 0; i < 150; i++) {
            text.append("| Item ").append(i).append(" | ").append(100 + i).append(" | ").append(i).append(" |\n");
        }
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.split(text.toString());
        List<MarkdownChunker.Chunk> tables = chunks.stream()
                .filter(c -> c.content().startsWith("| Product")).toList();
        assertTrue(tables.size() > 1, "超大表格应拆成多段");
        assertTrue(tables.stream().allMatch(c -> c.content().startsWith("| Product | Price | Qty |")
                && c.content().contains("| --- | --- | --- |")), "每段应重复表头");
        assertTrue(tables.stream().anyMatch(c -> c.content().contains("| Item 149 | 249 | 149 |")),
                "末行不应丢失");
    }

    @Test
    void doesNotSplitCodeBlock() {
        String code = "```java\n" + "System.out.println(\"hello\");\n".repeat(40) + "```";
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.split("## 示例\n\n" + code + "\n\n正文");
        assertTrue(chunks.stream().anyMatch(c -> c.content().startsWith("```java")
                && c.content().endsWith("```")), "代码块应保持原子");
    }
}

class MarkdownTextTableSplitTest {

    @org.junit.jupiter.api.Test
    void splitsLargeHtmlTableAtSafeRowspanBoundaries() {
        StringBuilder html = new StringBuilder("<table border=\"1\"><thead><tr><th>Group</th><th>Item</th></tr></thead><tbody>");
        for (int g = 0; g < 8; g++) {
            html.append("<tr><td rowspan=\"5\">G").append(g).append("</td><td>first</td></tr>");
            for (int i = 1; i < 5; i++) {
                html.append("<tr><td>").append(g).append("-").append(i).append("</td></tr>");
            }
        }
        html.append("</tbody></table>");

        java.util.List<String> parts = MarkdownText.splitTable(html.toString(), 500);

        org.junit.jupiter.api.Assertions.assertTrue(parts.size() > 1, "超大 HTML 表应拆分");
        for (String part : parts) {
            org.junit.jupiter.api.Assertions.assertTrue(part.contains("<thead>"), "每段应重复表头");
            org.junit.jupiter.api.Assertions.assertTrue(part.contains("rowspan=\"5\""), "rowspan 组内不应被切断");
        }
    }
}
