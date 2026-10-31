package com.platform.knowledge.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** 测试用表格结构指标：同时支持 HTML 表格和 Markdown 管道表。 */
final class TableStructureMetrics {

    record Metrics(int tables, int rows, int columns, int headerRows,
                   int cells, int nonEmptyCells, int emptyCells,
                   int rowSpans, int colSpans) {
    }

    private TableStructureMetrics() {
    }

    static Metrics fromText(String text) {
        if (text == null || text.isBlank()) return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        if (text.contains("<table")) return fromHtml(text);
        return fromMarkdown(text);
    }

    private static Metrics fromHtml(String text) {
        var doc = Jsoup.parseBodyFragment(text);
        var tables = doc.select("table");
        int rows = 0, columns = 0, headers = 0, cells = 0, nonEmpty = 0;
        int rowSpans = 0, colSpans = 0;
        for (Element table : tables) {
            List<List<String>> grid = new ArrayList<>();
            for (Element row : table.select("tr")) {
                List<String> values = new ArrayList<>();
                for (Element cell : row.select("td,th")) {
                    values.add(cell.text().trim());
                    int rowspan = intAttr(cell, "rowspan");
                    int colspan = intAttr(cell, "colspan");
                    if (rowspan > 1) rowSpans++;
                    if (colspan > 1) colSpans++;
                    for (int i = 1; i < colspan; i++) values.add("");
                }
                grid.add(values);
                rows++;
            }
            Element thead = table.selectFirst("thead");
            if (thead != null) headers += Math.max(1, thead.select("tr").size());
            else headers += Math.min(1, table.select("th").size() > 0 ? 1 : 0);
            for (List<String> row : grid) {
                columns = Math.max(columns, row.size());
                for (String value : row) {
                    cells++;
                    if (!value.isBlank()) nonEmpty++;
                }
            }
        }
        return new Metrics(tables.size(), rows, columns, headers, cells, nonEmpty,
                cells - nonEmpty, rowSpans, colSpans);
    }

    private static Metrics fromMarkdown(String text) {
        int rows = 0, columns = 0, cells = 0, nonEmpty = 0;
        boolean header = false;
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (!value.startsWith("|")) continue;
            if (value.matches("^\\|[\\s:|-]+\\|?$")) {
                header = true;
                continue;
            }
            String[] parts = value.replaceAll("^\\||\\|$", "").split("\\|", -1);
            List<String> values = new ArrayList<>();
            for (String part : parts) {
                String cell = part.trim();
                values.add(cell);
                cells++;
                if (!cell.isBlank()) nonEmpty++;
            }
            columns = Math.max(columns, values.size());
            rows++;
        }
        return new Metrics(rows > 0 ? 1 : 0, rows, columns, header ? 1 : 0,
                cells, nonEmpty, cells - nonEmpty, 0, 0);
    }

    private static int intAttr(Element element, String name) {
        try {
            return Math.max(1, Integer.parseInt(element.attr(name)));
        } catch (Exception e) {
            return 1;
        }
    }
}
