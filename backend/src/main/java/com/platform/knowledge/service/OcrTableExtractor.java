package com.platform.knowledge.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 tesseract TSV 输出重建扫描页文本与简单表格。
 * 只输出至少 3 行、最多列 >=3、列起点跨行对齐的表格，避免把正文误判成表格。
 */
final class OcrTableExtractor {

    private static final float LINE_TOLERANCE = 8f;
    private static final float CELL_GAP = 20f;
    private static final float ALIGN_TOLERANCE = 15f;
    private static final float ANCHOR_TOLERANCE = 28f;

    private OcrTableExtractor() {
    }

    record Result(String text, String table, double confidence) {
        Result(String text, String table) {
            this(text, table, 0);
        }
    }

    static Result extract(String tsv) {
        return extract(tsv, null);
    }

    /** 带页面图时优先尝试网格结构；无合并单元格则回退到 TSV 文本重建。 */
    static Result extract(String tsv, BufferedImage image) {
        List<Word> words = parseWords(tsv);
        if (words.isEmpty()) return new Result("", "");
        double confidence = words.stream().mapToInt(Word::confidence).average().orElse(0);
        List<Line> lines = groupLines(words);
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (!line.text.isBlank()) text.append(line.text.trim()).append('\n');
        }
        String table = "";
        if (image != null) {
            List<OcrGridTableExtractor.Box> boxes = words.stream()
                    .map(word -> new OcrGridTableExtractor.Box(word.text, word.left, word.top,
                            word.width, word.height))
                    .toList();
            table = OcrGridTableExtractor.extract(image, boxes);
            if (!table.isBlank()) table = decorateGridTable(table, lines);
        }
        if (table.isBlank()) table = toTable(lines);
        return new Result(text.toString().trim(), table, confidence);
    }

    private static List<Word> parseWords(String tsv) {
        List<Word> words = new ArrayList<>();
        if (tsv == null || tsv.isBlank()) return words;
        String[] rows = tsv.split("\\R");
        for (int i = 1; i < rows.length; i++) {
            String[] columns = rows[i].split("\\t", -1);
            if (columns.length < 12) continue;
            String value = columns[11].trim();
            if (value.isEmpty()) continue;
            try {
                int confidence = Math.round(Float.parseFloat(columns[10]));
                if (confidence < 0) continue;
                float left = Float.parseFloat(columns[6]);
                float top = Float.parseFloat(columns[7]);
                float width = Float.parseFloat(columns[8]);
                float height = Float.parseFloat(columns[9]);
                words.add(new Word(value, left, top, width, height, confidence));
            } catch (NumberFormatException ignored) {
                // 非文本行跳过
            }
        }
        return words;
    }

    private static List<Line> groupLines(List<Word> words) {
        words.sort(Comparator.comparingDouble((Word word) -> word.top)
                .thenComparingDouble(word -> word.left));
        List<Line> lines = new ArrayList<>();
        for (Word word : words) {
            Line line = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            float tolerance = Math.max(LINE_TOLERANCE, word.height * 0.6f);
            if (line == null || Math.abs(line.top - word.top) > tolerance) {
                line = new Line(word.top);
                lines.add(line);
            }
            line.words.add(word);
        }
        for (Line line : lines) {
            line.words.sort(Comparator.comparingDouble(word -> word.left));
            line.build();
        }
        return lines;
    }

    private static String toTable(List<Line> lines) {
        List<Line> candidates = lines.stream()
                .filter(line -> line.cells.size() >= 2)
                .filter(line -> !isCaption(line.text) && !isNote(line.text))
                .toList();
        if (candidates.size() < 3) return "";
        StringBuilder tables = new StringBuilder();
        for (List<Line> block : contiguousBlocks(candidates)) {
            String table = bestTableBlock(block, lines);
            if (table.isBlank()) continue;
            if (tables.length() > 0) tables.append('\n');
            tables.append(table);
        }
        return tables.toString();
    }

    /** 同一页可能有多段表格；按行间距切块，避免正文行把列锚点撑散。 */
    private static List<List<Line>> contiguousBlocks(List<Line> lines) {
        List<List<Line>> blocks = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        Line previous = null;
        for (Line line : lines) {
            if (previous != null) {
                float gap = line.top - previous.top;
                float height = Math.max(previous.height(), line.height());
                if (gap > Math.max(40f, height * 5.0f)) {
                    if (current.size() >= 3) blocks.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(line);
            previous = line;
        }
        if (current.size() >= 3) blocks.add(current);
        return blocks;
    }

    /** 页眉/正文也可能形成 2–9 个“单元格”，逐级提高最小列数，选最完整的表。 */
    private static String bestTableBlock(List<Line> block, List<Line> lines) {
        String best = "";
        double bestDensity = 0;
        int bestRows = 0;
        for (int minCells = 2; minCells <= 8; minCells++) {
            final int min = minCells;
            List<Line> candidates = block.stream()
                    .filter(line -> line.cells.size() >= min).toList();
            if (candidates.size() < 3) continue;
            String table = toTableBlock(candidates, lines);
            if (table.isBlank()) continue;
            int cells = count(table, "<td") + count(table, "<th");
            int empty = count(table, "></td>") + count(table, "></th>");
            if (cells == 0) continue;
            double density = (double) (cells - empty) / cells;
            // 优先选择填充率高、行数足够的版本；避免把正文空白行拼成超大表。
            if (density > bestDensity + 0.02
                    || (Math.abs(density - bestDensity) <= 0.02 && candidates.size() > bestRows)) {
                bestDensity = density;
                bestRows = candidates.size();
                best = table;
            }
        }
        return best;
    }

    private static String toTableBlock(List<Line> candidates, List<Line> lines) {
        if (candidates.size() < 3) return "";
        int maxColumns = candidates.stream().mapToInt(line -> line.cells.size()).max().orElse(0);
        if (maxColumns < 2) return "";
        boolean hasDigit = candidates.stream().anyMatch(line -> line.cells.stream()
                .anyMatch(cell -> cell.text.matches(".*\\d.*")));
        if (maxColumns == 2 && !hasDigit) return "";
        boolean alignedTable = aligned(candidates);
        List<Float> anchors = anchors(candidates);
        if (!alignedTable || anchors.size() < 2 || anchors.size() > 30) return "";
        String caption = findCaption(lines, candidates.get(0).top);
        List<String> notes = findNotes(lines, candidates.get(candidates.size() - 1).top);

        StringBuilder table = new StringBuilder("<table border=\"1\">\n");
        if (caption != null) table.append("<caption>").append(escape(caption)).append("</caption>\n");
        int headers = headerRows(candidates);
        if (headers > 0) {
            table.append("<thead>\n");
            for (int i = 0; i < headers; i++) {
                table.append(renderHeaderRow(candidates.get(i), anchors));
            }
            table.append("</thead>\n");
        }
        table.append("<tbody>\n");
        int[][] spans = inferRowSpans(candidates, headers, anchors);
        for (int i = headers; i < candidates.size(); i++) {
            String[] cells = mapCells(candidates.get(i), anchors);
            table.append("<tr>");
            for (int col = 0; col < cells.length; col++) {
                if (coveredByRowSpan(spans, i, col)) continue;
                String cell = cells[col];
                int span = spans[i][col];
                table.append("<td");
                if (span > 1) table.append(" rowspan=\"").append(span).append("\"");
                table.append(">").append(cell == null ? "" : escape(cell)).append("</td>");
            }
            table.append("</tr>\n");
        }
        table.append("</tbody>\n</table>");
        for (String note : notes) {
            table.append("\n<p class=\"table-note\">").append(escape(note)).append("</p>");
        }
        return table.toString();
    }

    /** 网格表格仍保留表格标题/脚注，避免图像网格路径丢失 caption。 */
    private static String decorateGridTable(String table, List<Line> lines) {
        List<Line> candidates = lines.stream()
                .filter(line -> line.cells.size() >= 2)
                .filter(line -> !isCaption(line.text) && !isNote(line.text))
                .toList();
        if (candidates.isEmpty()) return table;
        String caption = findCaption(lines, candidates.get(0).top);
        List<String> notes = findNotes(lines, candidates.get(candidates.size() - 1).top);
        StringBuilder html = new StringBuilder(table);
        if (caption != null) {
            int open = html.indexOf(">");
            if (open >= 0) html.insert(open + 1, "\n<caption>" + escape(caption) + "</caption>");
        }
        for (String note : notes) {
            html.append("\n<p class=\"table-note\">").append(escape(note)).append("</p>");
        }
        return html.toString();
    }

    private static int[][] inferRowSpans(List<Line> lines, int headers, List<Float> anchors) {
        int[][] spans = new int[lines.size()][];
        for (int i = 0; i < lines.size(); i++) spans[i] = new int[anchors.size()];
        for (int r = headers; r < lines.size(); r++) {
            String[] values = mapCells(lines.get(r), anchors);
            for (int c = 0; c < values.length; c++) {
                if (coveredByRowSpan(spans, r, c)) continue;
                String value = values[c];
                if (value == null || value.isBlank() || value.matches("[-+]?\\d+([.,]\\d+)?")) continue;
                int span = 1;
                while (span < 50 && r + span < lines.size()) {
                    String[] next = mapCells(lines.get(r + span), anchors);
                    if (next[c] != null && !next[c].isBlank()) break;
                    boolean other = false;
                    for (int o = 0; o < next.length; o++) {
                        if (o != c && next[o] != null && !next[o].isBlank()) { other = true; break; }
                    }
                    if (!other) break;
                    span++;
                }
                if (span > 1) spans[r][c] = span;
            }
        }
        return spans;
    }

    private static boolean coveredByRowSpan(int[][] spans, int row, int column) {
        for (int r = 0; r < row; r++) {
            if (spans[r][column] > row - r) return true;
        }
        return false;
    }

    /** 相邻扫描页表头一致时合并 tbody；表头只保留一次。 */
    static String mergeTables(String previous, String current) {
        if (previous == null || previous.isBlank()) return current == null ? "" : current;
        if (current == null || current.isBlank()) return previous;
        String prevHead = headerOf(previous);
        String currHead = headerOf(current);
        String prevBody = bodyOf(previous);
        String currBody = bodyOf(current);
        if (prevHead.isBlank() || !prevHead.equals(currHead) || prevBody.isBlank() || currBody.isBlank()) {
            String mapped = mergeByHeaderNames(previous, current);
            return mapped == null ? previous + "\n" + current : mapped;
        }
        int close = previous.indexOf("</tbody>");
        if (close < 0) close = previous.lastIndexOf("</table>");
        if (close < 0) return previous + "\n" + current;
        return previous.substring(0, close) + currBody + previous.substring(close);
    }

    /** 按表头名称对齐列，支持插入/删除/换序；表头和数据区 colspan 展开。 */
    private static String mergeByHeaderNames(String previous, String current) {
        List<String> prevHead = headerCells(previous);
        List<String> currHead = headerCells(current);
        if (prevHead == null || currHead == null) return null;
        List<List<String>> prevRows = bodyRows(previous, prevHead.size());
        List<List<String>> currRows = bodyRows(current, currHead.size());
        if (prevHead == null || currHead == null || prevRows == null || currRows == null) return null;
        java.util.LinkedHashMap<String, Integer> union = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
        for (String name : prevHead) {
            if (name == null || name.isBlank()) return null;
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (union.putIfAbsent(key, union.size()) != null) return null;
            labels.putIfAbsent(key, name);
        }
        int common = 0;
        for (String name : currHead) {
            if (name == null || name.isBlank()) return null;
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (union.containsKey(key)) common++;
            else {
                union.put(key, union.size());
                labels.putIfAbsent(key, name);
            }
        }
        if (common < 2 || union.size() > 30) return null;
        int[] prevMap = headerMap(prevHead, union);
        int[] currMap = headerMap(currHead, union);
        if (prevMap == null || currMap == null) return null;
        if (prevRows.stream().anyMatch(row -> row.size() != prevHead.size())
                || currRows.stream().anyMatch(row -> row.size() != currHead.size())) return null;

        StringBuilder html = new StringBuilder("<table border=\"1\">\n");
        int captionStart = previous.indexOf("<caption>");
        if (captionStart >= 0) {
            int captionEnd = previous.indexOf("</caption>", captionStart);
            if (captionEnd > captionStart) {
                html.append(previous, captionStart, captionEnd + 10).append("\n");
            }
        }
        html.append("<thead>\n<tr>");
        for (String name : union.keySet()) {
            html.append("<th>").append(escape(labels.getOrDefault(name, name))).append("</th>");
        }
        html.append("</tr>\n</thead>\n<tbody>\n");
        appendMappedRows(html, prevRows, prevMap, union.size());
        appendMappedRows(html, currRows, currMap, union.size());
        html.append("</tbody>\n</table>");
        java.util.regex.Matcher note = java.util.regex.Pattern
                .compile("<p class=\"table-note\">(.*?)</p>", java.util.regex.Pattern.DOTALL)
                .matcher(previous + "\n" + current);
        while (note.find()) html.append("\n<p class=\"table-note\">").append(note.group(1)).append("</p>");
        return html.toString();
    }

    private static int[] headerMap(List<String> head, java.util.Map<String, Integer> union) {
        int[] map = new int[head.size()];
        for (int i = 0; i < map.length; i++) {
            Integer target = union.get(head.get(i).toLowerCase(java.util.Locale.ROOT));
            if (target == null) return null;
            map[i] = target;
        }
        return map;
    }

    private static void appendMappedRows(StringBuilder html, List<List<String>> rows,
                                         int[] map, int columns) {
        for (List<String> row : rows) {
            String[] cells = new String[columns];
            for (int i = 0; i < map.length && i < row.size(); i++) cells[map[i]] = row.get(i);
            html.append("<tr>");
            for (String cell : cells) {
                html.append("<td>").append(cell == null ? "" : escape(cell)).append("</td>");
            }
            html.append("</tr>\n");
        }
    }

    private static List<String> headerCells(String html) {
        int start = html.indexOf("<thead>");
        if (start < 0) return null;
        int end = html.indexOf("</thead>", start);
        if (end < 0) return null;
        String head = html.substring(start, end);
        if (head.contains("colspan") || head.contains("rowspan")) {
            return headerCellsSpanAware(html);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<th([^>]*)>(.*?)</th>", java.util.regex.Pattern.DOTALL).matcher(head);
        List<String> cells = new ArrayList<>();
        while (matcher.find()) {
            cells.add(unescape(matcher.group(2).trim()));
        }
        return cells.isEmpty() ? null : cells;
    }

    /** 展开 thead 的 colspan/rowspan，按最末级叶列生成唯一列键。 */
    private static List<String> headerCellsSpanAware(String html) {
        try {
            Element head = Jsoup.parse(html).selectFirst("thead");
            if (head == null) return null;
            List<Element> rows = head.select("tr");
            List<List<String>> grid = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                grid.add(new ArrayList<>());
                int col = 0;
                for (Element cell : rows.get(r).children()) {
                    String tag = cell.tagName();
                    if (!"th".equals(tag) && !"td".equals(tag)) continue;
                    while (col < grid.get(r).size() && grid.get(r).get(col) != null) col++;
                    int colspan = Math.max(1, intAttr(cell, "colspan"));
                    int rowspan = Math.max(1, intAttr(cell, "rowspan"));
                    String text = cell.text().trim();
                    for (int rr = r; rr < r + rowspan && rr < rows.size(); rr++) {
                        while (grid.size() <= rr) grid.add(new ArrayList<>());
                        for (int cc = col; cc < col + colspan; cc++) {
                            while (grid.get(rr).size() <= cc) grid.get(rr).add(null);
                            grid.get(rr).set(cc, text);
                        }
                    }
                    col += colspan;
                }
            }
            int columns = grid.stream().mapToInt(List::size).max().orElse(0);
            if (columns < 2) return null;
            List<String> keys = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                List<String> parts = new ArrayList<>();
                for (List<String> row : grid) {
                    String value = c < row.size() ? row.get(c) : null;
                    if (value == null || value.isBlank()) continue;
                    if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(value)) parts.add(value);
                }
                String key = String.join(" / ", parts).trim();
                if (key.isBlank()) return null;
                keys.add(key);
            }
            return keys;
        } catch (Exception e) {
            return null;
        }
    }

    private static int intAttr(Element cell, String name) {
        try {
            return Integer.parseInt(cell.attr(name));
        } catch (Exception e) {
            return 1;
        }
    }

    private static List<List<String>> bodyRows(String html, int columns) {
        Element tbody = Jsoup.parse(html).selectFirst("tbody");
        if (tbody == null || columns < 1) return null;
        List<List<String>> result = new ArrayList<>();
        int[] activeRowspans = new int[columns];
        for (Element tr : tbody.select("tr")) {
            List<Element> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                if ("td".equals(cell.tagName())) cells.add(cell);
            }
            List<String> row = new ArrayList<>();
            int cellIndex = 0;
            int col = 0;
            while (col < columns) {
                if (activeRowspans[col] > 0) {
                    activeRowspans[col]--;
                    row.add("");
                    col++;
                    continue;
                }
                if (cellIndex >= cells.size()) {
                    row.add("");
                    col++;
                    continue;
                }
                Element cell = cells.get(cellIndex++);
                int colspan = Math.max(1, intAttr(cell, "colspan"));
                int rowspan = Math.max(1, intAttr(cell, "rowspan"));
                row.add(unescape(cell.text().trim()));
                for (int i = 1; i < colspan && col + i < columns; i++) row.add("");
                for (int i = 0; i < colspan && col + i < columns; i++) {
                    if (rowspan > 1) activeRowspans[col + i] = rowspan - 1;
                }
                col += colspan;
            }
            if (cellIndex < cells.size()) return null;
            result.add(row);
        }
        return result;
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    private static String headerOf(String html) {
        int start = html.indexOf("<thead>");
        if (start >= 0) {
            int end = html.indexOf("</thead>", start);
            if (end > start) return html.substring(start + 7, end).replaceAll("\\s+", "");
        }
        int tr = html.indexOf("<tr>");
        if (tr < 0) return "";
        int end = html.indexOf("</tr>", tr);
        return end > tr ? html.substring(tr + 4, end).replaceAll("\\s+", "") : "";
    }

    private static String bodyOf(String html) {
        int start = html.indexOf("<tbody>");
        if (start >= 0) {
            int end = html.indexOf("</tbody>", start);
            return end > start ? html.substring(start + 7, end) : "";
        }
        int first = html.indexOf("</tr>");
        int end = html.lastIndexOf("</table>");
        return first >= 0 && end > first ? html.substring(first + 5, end) : "";
    }

    private static String renderHeaderRow(Line line, List<Float> anchors) {
        String[] cells = mapCells(line, anchors);
        StringBuilder html = new StringBuilder("<tr>");
        int covered = -1;
        for (int i = 0; i < cells.length; i++) {
            if (i <= covered) continue;
            if (cells[i] == null || cells[i].isBlank()) {
                html.append("<th></th>");
                continue;
            }
            int next = i + 1;
            while (next < cells.length && (cells[next] == null || cells[next].isBlank())) next++;
            int span = Math.max(1, next - i);
            html.append("<th");
            if (span > 1) html.append(" colspan=\"").append(span).append("\"");
            html.append(">").append(escape(cells[i])).append("</th>");
            covered = i + span - 1;
        }
        return html.append("</tr>\n").toString();
    }

    private static String[] mapCells(Line line, List<Float> anchors) {
        String[] cells = new String[anchors.size()];
        for (Cell cell : line.cells) {
            int column = nearest(anchors, cell.left);
            cells[column] = cells[column] == null ? cell.text : cells[column] + " " + cell.text;
        }
        return cells;
    }

    private static int headerRows(List<Line> lines) {
        int count = 0;
        for (Line line : lines) {
            boolean value = false;
            boolean numeric = false;
            for (Cell cell : line.cells) {
                if (cell.text.isBlank()) continue;
                value = true;
                if (cell.text.matches("[-+]?\\d+([.,]\\d+)?")) numeric = true;
            }
            if (!value || (numeric && !yearLabels(line))) break;
            count++;
        }
        return count >= lines.size() ? 0 : count;
    }

    private static boolean yearLabels(Line line) {
        boolean any = false;
        for (Cell cell : line.cells) {
            if (cell.text.isBlank()) continue;
            if (!cell.text.matches("(19|20)\\d{2}")) return false;
            any = true;
        }
        return any;
    }

    private static String findCaption(List<Line> lines, float tableTop) {
        String best = null;
        for (Line line : lines) {
            if (line.top >= tableTop - LINE_TOLERANCE || line.top < tableTop - 240f) continue;
            if (isCaption(line.text)) best = line.text;
        }
        return best;
    }

    private static List<String> findNotes(List<Line> lines, float tableBottom) {
        List<String> notes = new ArrayList<>();
        for (Line line : lines) {
            if (line.top <= tableBottom + LINE_TOLERANCE || line.top > tableBottom + 400f) continue;
            if (isNote(line.text)) {
                notes.add(line.text);
                if (notes.size() >= 3) break;
            }
        }
        return notes;
    }

    private static boolean isCaption(String text) {
        if (text == null || text.isBlank() || text.length() > 200) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("table") || text.contains("表")
                || lower.contains("figure") || text.contains("图");
    }

    private static boolean isNote(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("note") || lower.startsWith("注") || lower.startsWith("source")
                || lower.startsWith("来源") || text.startsWith("*") || text.startsWith("†");
    }

    private static List<Float> anchors(List<Line> lines) {
        List<Float> anchors = new ArrayList<>();
        for (Line line : lines) {
            for (Cell cell : line.cells) {
                if (anchors.stream().noneMatch(anchor -> Math.abs(anchor - cell.left) <= ANCHOR_TOLERANCE)) {
                    anchors.add(cell.left);
                }
            }
        }
        anchors.sort(Float::compare);
        return anchors;
    }

    private static boolean aligned(List<Line> lines) {
        int alignedRows = 0;
        for (Line line : lines) {
            int alignedCells = 0;
            for (Cell cell : line.cells) {
                boolean match = lines.stream().anyMatch(other -> other != line
                        && other.cells.stream().anyMatch(candidate ->
                        Math.abs(candidate.left - cell.left) <= ALIGN_TOLERANCE));
                if (match) alignedCells++;
            }
            if (alignedCells >= 2) alignedRows++;
        }
        return alignedRows >= 3;
    }

    private static int nearest(List<Float> anchors, float x) {
        int best = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            float current = Math.abs(anchors.get(i) - x);
            if (current < distance) {
                distance = current;
                best = i;
            }
        }
        return best;
    }

    private static int count(String text, String value) {
        int n = 0, i = 0;
        while ((i = text.indexOf(value, i)) >= 0) { n++; i += value.length(); }
        return n;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Word(String text, float left, float top, float width, float height, int confidence) {
    }

    private record Cell(String text, float left) {
    }

    private static final class Line {
        private final float top;
        private final List<Word> words = new ArrayList<>();
        private final List<Cell> cells = new ArrayList<>();
        private String text = "";

        private Line(float top) {
            this.top = top;
        }

        private float height() {
            return (float) words.stream().mapToDouble(Word::height).max().orElse(12f);
        }

        private void build() {
            StringBuilder lineText = new StringBuilder();
            StringBuilder cellText = new StringBuilder();
            float start = -1;
            float end = -1;
            for (Word word : words) {
                if (start >= 0 && word.left - end > CELL_GAP) {
                    cells.add(new Cell(cellText.toString().trim(), start));
                    cellText.setLength(0);
                    start = -1;
                }
                if (start < 0) start = word.left;
                if (!cellText.isEmpty()) cellText.append(' ');
                cellText.append(word.text);
                lineText.append(lineText.isEmpty() ? "" : " ").append(word.text);
                end = Math.max(end, word.left + word.width);
            }
            if (!cellText.isEmpty()) cells.add(new Cell(cellText.toString().trim(), start));
            text = lineText.toString();
        }
    }
}
