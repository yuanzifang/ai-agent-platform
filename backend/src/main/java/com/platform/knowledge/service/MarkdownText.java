package com.platform.knowledge.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Markdown/HTML 文本工具。
 *
 * <p>PDF/Office 表格提取器输出的是 HTML（为了保留 colspan/rowspan）；入库前把无合并单元格的
 * 简单表格转成 Markdown 管道表，降低 token 并提升大模型可读性。embedding/关键词索引用
 * {@link #toPlain(String)} 去标签纯文本，避免 {@code <td>} 等标记成为噪声。</p>
 */
final class MarkdownText {

    private MarkdownText() {
    }

    /** 把提取器输出的 HTML 表格转换为 Markdown；合并单元格表格保留 HTML。 */
    static String htmlTablesToMarkdown(String html) {
        if (html == null || html.isBlank()) return "";
        if (!html.toLowerCase(Locale.ROOT).contains("<table")) return html.trim();
        Document doc = Jsoup.parseBodyFragment(html);
        StringBuilder out = new StringBuilder();
        for (Node node : doc.body().childNodes()) {
            if (node instanceof Element el) {
                if ("table".equals(el.tagName())) {
                    out.append(tableToMarkdown(el)).append("\n\n");
                } else if (el.hasClass("table-note")) {
                    out.append("> ").append(el.text().trim()).append("\n\n");
                } else {
                    out.append(el.outerHtml()).append("\n\n");
                }
            } else if (node instanceof TextNode text && !text.text().isBlank()) {
                out.append(text.text().trim()).append("\n\n");
            }
        }
        return out.toString().trim();
    }

    /** 把大表格按行拆成多段，每段重复表头；含合并单元格的 HTML 表格保持原子。 */
    static List<String> splitTable(String table, int maxChars) {
        if (table == null || table.isBlank()) return List.of();
        String trimmed = table.trim();
        if (trimmed.length() <= maxChars) return List.of(trimmed);
        if (trimmed.startsWith("<")) return splitHtmlTable(trimmed, maxChars);
        return splitPipeTable(trimmed, maxChars);
    }

    private static List<String> splitPipeTable(String table, int maxChars) {
        String[] lines = table.split("\n");
        if (lines.length < 3) return List.of(table);
        String header = lines[0] + "\n" + lines[1];
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder(header);
        boolean hasRows = false;
        for (int i = 2; i < lines.length; i++) {
            String row = lines[i];
            if (row.isBlank()) continue;
            if (hasRows && part.length() + row.length() + 1 > maxChars) {
                parts.add(part.toString());
                part = new StringBuilder(header);
                hasRows = false;
            }
            part.append('\n').append(row);
            hasRows = true;
        }
        if (hasRows) parts.add(part.toString());
        return parts.isEmpty() ? List.of(table) : parts;
    }

    private static List<String> splitHtmlTable(String html, int maxChars) {
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            Element table = doc.selectFirst("table");
            if (table == null) return List.of(html);
            List<Element> bodyRows = table.select("tbody tr");
            if (bodyRows.isEmpty()) bodyRows = table.select("tr");
            if (bodyRows.size() < 3) return List.of(html);
            Element caption = table.selectFirst("caption");
            Element thead = table.selectFirst("thead");
            List<Element> headerRows = new ArrayList<>();
            String head;
            if (thead != null) {
                headerRows.addAll(thead.select("tr"));
                if (headerRows.isEmpty()) {
                    head = thead.outerHtml();
                } else {
                    head = "<thead>"
                            + headerRows.stream().map(Element::outerHtml).reduce("", String::concat)
                            + "</thead>";
                }
            } else if (!bodyRows.isEmpty()) {
                headerRows.add(bodyRows.remove(0));
                head = "<thead>"
                        + headerRows.stream().map(Element::outerHtml).reduce("", String::concat)
                        + "</thead>";
            } else {
                head = "";
            }
            head = (caption == null ? "" : caption.outerHtml()) + head;
            if (!headerRows.isEmpty()) bodyRows.removeAll(headerRows);
            // 表头自身有跨行时，表头无法安全重复，保持原子。
            if (headerRows.stream().anyMatch(row -> row.select("[rowspan]").stream()
                    .anyMatch(cell -> intAttr(cell, "rowspan") > 1))) {
                return List.of(html);
            }
            List<String> parts = new ArrayList<>();
            StringBuilder part = new StringBuilder("<table border=\"1\">\n").append(head).append("\n<tbody>");
            boolean hasRows = false;
            Map<Integer, Integer> activeRowSpans = new HashMap<>();
            for (Element row : bodyRows) {
                // 先消耗上一行留下的 rowspan；仍活动的列在本行会被跳过。
                activeRowSpans.entrySet().removeIf(e -> e.getValue() <= 0);
                activeRowSpans.replaceAll((col, remaining) -> remaining - 1);
                String rowHtml = row.outerHtml();
                if (hasRows && activeRowSpans.isEmpty()
                        && part.length() + rowHtml.length() > maxChars) {
                    parts.add(part.append("\n</tbody>\n</table>").toString());
                    part = new StringBuilder("<table border=\"1\">\n").append(head).append("\n<tbody>");
                    hasRows = false;
                }
                part.append('\n').append(rowHtml);
                hasRows = true;
                int column = 0;
                for (Element cell : row.select("td,th")) {
                    while (activeRowSpans.containsKey(column) && activeRowSpans.get(column) > 0) column++;
                    int colspan = Math.max(1, intAttr(cell, "colspan"));
                    int rowspan = Math.max(1, intAttr(cell, "rowspan"));
                    if (rowspan > 1) {
                        for (int c = 0; c < colspan; c++) {
                            activeRowSpans.put(column + c, rowspan - 1);
                        }
                    }
                    column += colspan;
                }
            }
            if (hasRows) parts.add(part.append("\n</tbody>\n</table>").toString());
            if (parts.size() <= 1) return List.of(html);
            // 只有真正找到安全切分点时才拆；否则保持原表原子。
            return parts;
        } catch (Exception e) {
            return List.of(html);
        }
    }

    private static int intAttr(Element element, String name) {
        try {
            return Integer.parseInt(element.attr(name));
        } catch (Exception e) {
            return 1;
        }
    }

    /** 表格标题/脚注文本，供解析时从正文流中去重。 */
    static List<String> tableSideTexts(String html) {
        List<String> texts = new ArrayList<>();
        if (html == null || html.isBlank() || !html.toLowerCase(Locale.ROOT).contains("<table")) {
            return texts;
        }
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            for (Element caption : doc.select("caption")) {
                if (!caption.text().isBlank()) texts.add(caption.text().trim());
            }
            for (Element note : doc.select("p.table-note")) {
                if (!note.text().isBlank()) texts.add(note.text().trim());
            }
        } catch (Exception ignored) {
            // 表格 HTML 损坏时不影响正文
        }
        return texts;
    }

    private static String tableToMarkdown(Element table) {
        if (hasMergedCells(table)) return table.outerHtml();

        List<Element> allRows = table.select("tr");
        List<Element> headRows = table.select("thead tr");
        if (headRows.isEmpty()) {
            Element thead = table.selectFirst("thead");
            if (thead != null && !thead.select("th,td").isEmpty()) headRows = List.of(thead);
        }
        List<Element> bodyRows = table.select("tbody tr");
        if (bodyRows.isEmpty()) {
            bodyRows = new ArrayList<>(allRows);
            bodyRows.removeAll(headRows);
        }
        int columns = 0;
        for (Element row : allRows) {
            columns = Math.max(columns, row.select("th,td").size());
        }
        for (Element row : headRows) {
            columns = Math.max(columns, row.select("th,td").size());
        }
        if (columns == 0) return table.outerHtml();
        if (headRows.isEmpty()) {
            if (bodyRows.isEmpty()) return table.outerHtml();
            headRows = List.of(bodyRows.remove(0));
        }

        StringBuilder out = new StringBuilder();
        Element caption = table.selectFirst("caption");
        if (caption != null && !caption.text().isBlank()) {
            out.append("**").append(escapePipe(caption.text().trim())).append("**\n\n");
        }
        for (Element row : headRows) {
            out.append(rowLine(row, columns)).append('\n');
        }
        out.append(separator(columns)).append('\n');
        for (Element row : bodyRows) {
            out.append(rowLine(row, columns)).append('\n');
        }
        return out.toString().trim();
    }

    private static boolean hasMergedCells(Element table) {
        for (Element cell : table.select("th,td")) {
            if (span(cell, "colspan") > 1 || span(cell, "rowspan") > 1) return true;
        }
        return false;
    }

    private static int span(Element cell, String attribute) {
        try {
            String value = cell.attr(attribute);
            return value.isBlank() ? 1 : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String rowLine(Element row, int columns) {
        StringBuilder line = new StringBuilder("|");
        List<Element> cells = row.select("th,td");
        for (int c = 0; c < columns; c++) {
            String value = c < cells.size() ? cells.get(c).text().trim() : "";
            line.append(' ').append(escapePipe(value)).append(" |");
        }
        return line.toString();
    }

    private static String separator(int columns) {
        StringBuilder line = new StringBuilder("|");
        for (int c = 0; c < columns; c++) line.append(" --- |");
        return line.toString();
    }

    private static String escapePipe(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", " ").replace("\n", "<br>");
    }

    /**
     * 展示文本 → 检索纯文本：去 HTML 注释/标签、Markdown 标记，压缩空白。
     * 只用于 embedding/关键词索引，不改动入库展示内容。
     */
    static String toPlain(String text) {
        if (text == null || text.isBlank()) return "";
        String value = text.replaceAll("(?s)<!--.*?-->", " ");
        if (value.matches("(?s).*<[a-zA-Z!/][^>]*>.*")) {
            try {
                value = Jsoup.parse(value).text();
            } catch (Exception ignored) {
                // 解析失败时继续用正则清理
            }
        }
        value = value.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        value = value.replaceAll("(?m)^\\s*>\\s?", "");
        value = value.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        value = value.replaceAll("(?m)^\\s*\\d+[.)]\\s+", "");
        value = value.replaceAll("!\\[([^\\]]*)]\\([^)]*\\)", "$1");
        value = value.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        value = value.replaceAll("[`*_~]", "");
        value = value.replaceAll("\\|", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF00 && c <= 0xFFEF)
                || (c >= 0x3000 && c <= 0x303F);
    }

    static boolean endsWithCjk(String value) {
        if (value == null || value.isEmpty()) return false;
        return isCjk(value.charAt(value.length() - 1));
    }

    static boolean startsWithCjk(String value) {
        if (value == null || value.isEmpty()) return false;
        return isCjk(value.charAt(0));
    }
}
