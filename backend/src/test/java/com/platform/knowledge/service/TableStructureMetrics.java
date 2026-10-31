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

    record Matrix(List<List<String>> rows, int headerRows) {
        int columns() {
            return rows.stream().mapToInt(List::size).max().orElse(0);
        }
    }

    record Accuracy(double precision, double recall, double f1,
                    int matches, int expectedCells, int actualCells) {
    }

    private TableStructureMetrics() {
    }

    static Metrics fromText(String text) {
        if (text == null || text.isBlank()) return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        if (text.contains("<table")) return fromHtml(text);
        return fromMarkdown(text);
    }

    static Matrix matrix(String text) {
        if (text == null || text.isBlank()) return new Matrix(List.of(), 0);
        if (text.contains("<table")) return htmlMatrix(text);
        return markdownMatrix(text);
    }

    static Accuracy compare(Matrix expected, Matrix actual) {
        int rows = Math.max(expected.rows().size(), actual.rows().size());
        int expectedCells = 0, actualCells = 0, matches = 0;
        for (int r = 0; r < rows; r++) {
            int cols = Math.max(r < expected.rows().size() ? expected.rows().get(r).size() : 0,
                    r < actual.rows().size() ? actual.rows().get(r).size() : 0);
            for (int c = 0; c < cols; c++) {
                String e = cell(expected, r, c);
                String a = cell(actual, r, c);
                if (!e.isBlank()) expectedCells++;
                if (!a.isBlank()) actualCells++;
                if (!e.isBlank() && normalize(e).equals(normalize(a))) matches++;
            }
        }
        double precision = actualCells == 0 ? 0 : (double) matches / actualCells;
        double recall = expectedCells == 0 ? 0 : (double) matches / expectedCells;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Accuracy(precision, recall, f1, matches, expectedCells, actualCells);
    }

    private static Matrix htmlMatrix(String text) {
        var doc = Jsoup.parseBodyFragment(text);
        var table = doc.selectFirst("table");
        if (table == null) return new Matrix(List.of(), 0);
        List<List<String>> grid = new ArrayList<>();
        int row = 0;
        for (Element tr : table.select("tr")) {
            ensureRow(grid, row);
            int col = 0;
            for (Element cell : tr.select("td,th")) {
                while (col < grid.get(row).size() && grid.get(row).get(col) != null) col++;
                int rowspan = intAttr(cell, "rowspan");
                int colspan = intAttr(cell, "colspan");
                for (int rr = 0; rr < rowspan; rr++) {
                    ensureRow(grid, row + rr);
                    for (int cc = 0; cc < colspan; cc++) {
                        int c = col + cc;
                        while (grid.get(row + rr).size() <= c) grid.get(row + rr).add(null);
                        grid.get(row + rr).set(c, rr == 0 && cc == 0 ? cell.text().trim() : "");
                    }
                }
                col += colspan;
            }
            row++;
        }
        int headerRows = table.selectFirst("thead") == null ? 0
                : Math.max(1, table.selectFirst("thead").select("tr").size());
        return new Matrix(grid, headerRows);
    }

    private static Matrix markdownMatrix(String text) {
        List<List<String>> rows = new ArrayList<>();
        boolean header = false;
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (!value.startsWith("|")) continue;
            if (value.matches("^\\|[\\s:|-]+\\|?$")) {
                header = true;
                continue;
            }
            String[] parts = value.replaceAll("^\\||\\|$", "").split("\\|", -1);
            List<String> row = new ArrayList<>();
            for (String part : parts) row.add(part.trim());
            rows.add(row);
        }
        return new Matrix(rows, header ? 1 : 0);
    }

    private static void ensureRow(List<List<String>> grid, int row) {
        while (grid.size() <= row) grid.add(new ArrayList<>());
    }

    private static String cell(Matrix matrix, int row, int col) {
        if (row >= matrix.rows().size()) return "";
        List<String> cells = matrix.rows().get(row);
        return col >= cells.size() || cells.get(col) == null ? "" : cells.get(col);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\s+", "").trim();
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
