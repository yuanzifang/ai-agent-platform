package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * PDF 表格轻量重建：按文本坐标把同一行的单元格拼成 HTML 表格。
 *
 * <p>只处理带文本层的 PDF；扫描件仍走 OCR/图片链路。为避免把普通正文误判为表格，
 * 仅当一页出现至少 3 行、最多列数至少 3、且列起始位置跨行对齐时才输出。
 * 跨页续表在上一页表格接近页底、下一页内容接近页顶且列锚点一致时合并。</p>
 */
final class PdfTableExtractor {

    private static final float CELL_GAP = 7f;
    private static final float ROW_TOLERANCE = 3f;
    private static final float ALIGN_TOLERANCE = 10f;
    private static final int MAX_PAGES = 50;
    private static final int MAX_CELL_LENGTH = 120;

    private PdfTableExtractor() {
    }

    static String extract(PDDocument doc) {
        StringBuilder html = new StringBuilder();
        try {
            List<Table> tables = new ArrayList<>();
            Table previous = null;
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                List<TextPosition> glyphs = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        glyphs.add(text);
                        super.processTextPosition(text);
                    }
                };
                stripper.setSortByPosition(true);
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.getText(doc);

                PDPage page = doc.getPage(pageIndex);
                float pageHeight = page.getMediaBox().getHeight();
                Table current = tableForPage(groupRows(glyphs), previous, pageHeight);
                if (current == null) {
                    previous = null;
                    continue;
                }
                if (current.continuation && previous != null) {
                    mergeContinuation(previous, current);
                } else {
                    tables.add(current);
                    previous = current;
                }
            }
            for (Table table : tables) {
                html.append('\n').append(render(table)).append('\n');
            }
        } catch (Exception e) {
            return "";
        }
        return html.toString();
    }

    private static Table tableForPage(List<Row> captured, Table previous, float pageHeight) {
        List<Row> rows = mergeRows(captured);
        List<Row> candidates = rows.stream().filter(row -> row.cells.size() >= 2).toList();
        if (candidates.size() >= 3) {
            int maxColumns = candidates.stream().mapToInt(row -> row.cells.size()).max().orElse(0);
            List<Float> anchors = columnAnchors(candidates);
            if (maxColumns >= 3 && anchors.size() >= 3 && anchors.size() <= 30
                    && columnsAligned(candidates)) {
                float minY = candidates.stream().map(row -> row.y).min(Float::compare).orElse(0f);
                float maxY = candidates.stream().map(row -> row.y).max(Float::compare).orElse(0f);
                float maxHeight = candidates.stream().map(row -> row.height).max(Float::compare).orElse(12f);
                List<LogicalRow> logical = logicalRows(rows, anchors, minY,
                        maxY + Math.max(12f, maxHeight * 1.8f));
                if (logical.size() >= 3) {
                    Table table = new Table(logical, anchors, pageHeight, false);
                    table.caption = findCaption(rows, minY);
                    table.notes = findNotes(rows, maxY);
                    if (previous != null && isCrossPage(previous, table, pageHeight)) {
                        table.continuation = true;
                    }
                    return table;
                }
            }
        }
        // 续页可能只有 1–2 行，达不到普通表格阈值；沿用上一页列锚点识别。
        if (previous != null && previous.lastY >= previous.pageHeight * 0.65f) {
            float topLimit = pageHeight * 0.45f;
            List<Row> topRows = rows.stream().filter(row -> row.y >= 0 && row.y <= topLimit).toList();
            List<LogicalRow> logical = logicalRows(topRows, previous.anchors, -Float.MAX_VALUE, topLimit);
            long nonEmpty = logical.stream().flatMap(row -> java.util.Arrays.stream(row.values))
                    .filter(Objects::nonNull).count();
            if (!logical.isEmpty() && nonEmpty >= 2) {
                Table table = new Table(logical, previous.anchors, pageHeight, true);
                return table;
            }
        }
        return null;
    }

    private static boolean isCrossPage(Table previous, Table current, float pageHeight) {
        if (previous.lastY < previous.pageHeight * 0.65f
                || current.firstY > pageHeight * 0.45f) return false;
        if (previous.anchors.size() == current.anchors.size()
                && anchorsOverlap(previous.anchors, current.anchors)) return true;
        // 列位置平移或新增列：要求重复表头与共同前缀，避免把另一张表误合并。
        return compatibleHeader(previous, current);
    }

    private static boolean anchorsOverlap(List<Float> left, List<Float> right) {
        int matches = 0;
        for (Float a : left) {
            if (right.stream().anyMatch(b -> Math.abs(a - b) <= ALIGN_TOLERANCE)) matches++;
        }
        return matches >= 2;
    }

    private static void mergeContinuation(Table previous, Table current) {
        LogicalRow previousHeader = previous.rows.isEmpty() ? null : previous.rows.get(0);
        LogicalRow currentHeader = current.rows.isEmpty() ? null : current.rows.get(0);
        boolean duplicateHeader = !previous.rows.isEmpty() && !current.rows.isEmpty()
                && (sameRow(previousHeader, currentHeader) || compatibleHeader(previous, current));
        if (duplicateHeader) current.rows.remove(0);
        if (!current.rows.isEmpty()) {
            if (previous.anchors.size() == current.anchors.size()) {
                previous.rows.addAll(current.rows);
            } else {
                List<Float> union = unionAnchors(previous.anchors, current.anchors);
                List<LogicalRow> oldRows = remapRows(previous.rows, previous.anchors, union);
                List<LogicalRow> newRows = remapRows(current.rows, current.anchors, union);
                if (duplicateHeader && currentHeader != null && !oldRows.isEmpty()) {
                    LogicalRow mappedHeader = remapRows(List.of(currentHeader), current.anchors, union).get(0);
                    LogicalRow previousHeaderMapped = oldRows.get(0);
                    for (int i = 0; i < union.size(); i++) {
                        if (previousHeaderMapped.values[i] == null && mappedHeader.values[i] != null) {
                            previousHeaderMapped.values[i] = mappedHeader.values[i];
                        }
                    }
                }
                previous.rows.clear();
                previous.rows.addAll(oldRows);
                previous.rows.addAll(newRows);
                previous.anchors = union;
            }
            previous.lastY = current.lastY;
            previous.pageHeight = current.pageHeight;
        }
        if (previous.notes.size() < 3) {
            previous.notes.addAll(current.notes.subList(0, Math.min(current.notes.size(), 3 - previous.notes.size())));
        }
    }

    private static List<Float> unionAnchors(List<Float> left, List<Float> right) {
        List<Float> union = new ArrayList<>(left);
        for (Float anchor : right) {
            if (union.stream().noneMatch(existing -> Math.abs(existing - anchor) <= ALIGN_TOLERANCE)) {
                union.add(anchor);
            }
        }
        union.sort(Float::compare);
        return union;
    }

    private static List<LogicalRow> remapRows(List<LogicalRow> rows, List<Float> source, List<Float> target) {
        List<LogicalRow> remapped = new ArrayList<>();
        for (LogicalRow row : rows) {
            LogicalRow copy = new LogicalRow(row.lastY, row.height, target.size());
            for (int i = 0; i < source.size() && i < row.values.length; i++) {
                if (row.values[i] == null) continue;
                int column = nearestAnchor(target, source.get(i));
                copy.values[column] = copy.values[column] == null
                        ? row.values[i] : copy.values[column] + " " + row.values[i];
            }
            remapped.add(copy);
        }
        return remapped;
    }

    private static boolean compatibleHeader(Table previous, Table current) {
        if (previous.rows.isEmpty() || current.rows.isEmpty()) return false;
        LogicalRow left = previous.rows.get(0);
        LogicalRow right = current.rows.get(0);
        int common = Math.min(left.values.length, right.values.length);
        if (common < 2) return false;
        for (int i = 0; i < common; i++) {
            String a = left.values[i] == null ? "" : left.values[i].trim();
            String b = right.values[i] == null ? "" : right.values[i].trim();
            if (!a.equalsIgnoreCase(b)) return false;
        }
        return true;
    }

    private static boolean sameRow(LogicalRow left, LogicalRow right) {
        if (left.values.length != right.values.length) return false;
        for (int i = 0; i < left.values.length; i++) {
            String a = left.values[i] == null ? "" : left.values[i].trim();
            String b = right.values[i] == null ? "" : right.values[i].trim();
            if (!a.equalsIgnoreCase(b)) return false;
        }
        return true;
    }

    private static String render(Table table) {
        StringBuilder html = new StringBuilder("<table border=\"1\">\n");
        if (table.caption != null) {
            html.append("<caption>").append(escape(table.caption)).append("</caption>\n");
        }
        int headers = headerRows(table.rows);
        if (headers > 0) {
            html.append("<thead>\n");
            for (int i = 0; i < headers; i++) {
                renderHeaderRow(html, table.rows.get(i));
            }
            html.append("</thead>\n");
        }
        html.append("<tbody>\n");
        int[][] spans = inferRowSpans(table.rows, headers);
        for (int i = headers; i < table.rows.size(); i++) {
            html.append("<tr>");
            for (int col = 0; col < table.rows.get(i).values.length; col++) {
                if (coveredByRowSpan(spans, i, col)) continue;
                String cell = table.rows.get(i).values[col];
                int span = spans[i][col];
                html.append("<td");
                if (span > 1) html.append(" rowspan=\"").append(span).append("\"");
                html.append(">").append(cell == null ? "" : escape(cell)).append("</td>");
            }
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table>");
        for (String note : table.notes) {
            html.append("\n<p class=\"table-note\">").append(escape(note)).append("</p>");
        }
        return html.toString();
    }

    /**
     * 保守推断纵向合并：非数值列、最多合并 10 行；后续行该列为空且其他列有内容时合并。
     * 复杂合并单元格仍需 PDF 网格线/边框分析，避免把大段空单元误判为 rowspan。
     */
    private static int[][] inferRowSpans(List<LogicalRow> rows, int headers) {
        int maxSpan = 10;
        int[][] spans = new int[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            spans[r] = new int[rows.get(r).values.length];
        }
        for (int r = headers; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).values.length; c++) {
                if (coveredByRowSpan(spans, r, c)) continue;
                String value = rows.get(r).values[c];
                if (value == null || value.isBlank() || value.matches("[-+]?\\d+([.,]\\d+)?%?")) continue;
                int span = 1;
                while (span < maxSpan && r + span < rows.size()) {
                    LogicalRow next = rows.get(r + span);
                    if (next.values[c] != null && !next.values[c].isBlank()) break;
                    boolean hasOther = false;
                    for (int other = 0; other < next.values.length; other++) {
                        if (other != c && next.values[other] != null && !next.values[other].isBlank()) {
                            hasOther = true;
                            break;
                        }
                    }
                    if (!hasOther) break;
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

    /** 首个数值数据行之前的连续行视为表头，支持两行以上的多级表头。 */
    private static int headerRows(List<LogicalRow> rows) {
        int count = 0;
        for (LogicalRow row : rows) {
            boolean value = false;
            boolean numeric = false;
            for (String cell : row.values) {
                if (cell == null || cell.isBlank()) continue;
                value = true;
                if (cell.matches("[-+]?\\d+([.,]\\d+)?%?")) numeric = true;
            }
            if (!value || (numeric && !yearLabels(row))) break;
            count++;
        }
        return count >= rows.size() ? 0 : count;
    }

    private static boolean yearLabels(LogicalRow row) {
        boolean any = false;
        for (String cell : row.values) {
            if (cell == null || cell.isBlank()) continue;
            if (!cell.matches("(19|20)\\d{2}")) return false;
            any = true;
        }
        return any;
    }

    private static void renderHeaderRow(StringBuilder html, LogicalRow row) {
        String[] values = row.values;
        int covered = -1;
        for (int i = 0; i < values.length; i++) {
            if (i <= covered) continue;
            if (values[i] == null || values[i].isBlank()) {
                html.append("<th></th>");
                continue;
            }
            int next = i + 1;
            while (next < values.length && (values[next] == null || values[next].isBlank())) next++;
            int span = Math.max(1, next - i);
            html.append("<th");
            if (span > 1) html.append(" colspan=\"").append(span).append("\"");
            html.append(">").append(escape(values[i])).append("</th>");
            covered = i + span - 1;
        }
    }

    private static String findCaption(List<Row> rows, float tableTop) {
        String best = null;
        for (Row row : rows) {
            if (row.y >= tableTop - ROW_TOLERANCE || row.y < tableTop - 80f) continue;
            String text = rowText(row);
            if (isCaption(text)) best = text;
        }
        return best;
    }

    private static List<String> findNotes(List<Row> rows, float tableBottom) {
        List<String> notes = new ArrayList<>();
        for (Row row : rows) {
            if (row.y <= tableBottom + ROW_TOLERANCE || row.y > tableBottom + 120f) continue;
            String text = rowText(row);
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("note") || lower.startsWith("注") || lower.startsWith("source")
                    || lower.startsWith("来源") || text.startsWith("*") || text.startsWith("†")) {
                notes.add(text);
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

    private static String rowText(Row row) {
        StringBuilder text = new StringBuilder();
        for (Cell cell : row.cells) {
            if (!text.isEmpty()) text.append(' ');
            text.append(cell.text);
        }
        return text.toString().trim();
    }

    private static List<Cell> cells(List<TextPosition> positions) {
        List<TextPosition> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
        List<Cell> cells = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        float start = -1;
        float end = -1;
        for (TextPosition position : sorted) {
            String unicode = position.getUnicode();
            if (unicode == null || unicode.isEmpty()
                    || "\n".equals(unicode) || "\r".equals(unicode) || "\t".equals(unicode)) {
                continue;
            }
            float x = position.getXDirAdj();
            if (start >= 0 && x - end > CELL_GAP) {
                addCell(cells, text, start, end);
                text.setLength(0);
                start = -1;
                end = -1;
            }
            if (start < 0) start = x;
            text.append(unicode);
            end = Math.max(end, x + Math.max(position.getWidthDirAdj(), 0));
        }
        addCell(cells, text, start, end);
        return cells;
    }

    private static void addCell(List<Cell> cells, StringBuilder text, float start, float end) {
        String value = text.toString().trim();
        if (!value.isEmpty() && value.length() <= MAX_CELL_LENGTH) {
            cells.add(new Cell(value, start, end));
        }
    }

    /**
     * 把物理文本行合并为逻辑表格行：单元格换行后的续行只含少量单元格，
     * 且与上一逻辑行垂直间距接近行高，按列锚点并入上一行。
     */
    private static List<LogicalRow> logicalRows(List<Row> rows, List<Float> anchors,
                                                float minY, float maxYAllowed) {
        List<LogicalRow> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.cells.isEmpty() || row.y < minY - ROW_TOLERANCE) continue;
            if (row.y > maxYAllowed) break;
            LogicalRow last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && isContinuation(last, row, anchors)) {
                for (Cell cell : row.cells) {
                    int column = nearestAnchor(anchors, cell.start);
                    last.values[column] = last.values[column] == null
                            ? cell.text : last.values[column] + " " + cell.text;
                }
                last.lastY = row.y;
                last.height = Math.max(last.height, row.height);
            } else {
                LogicalRow logical = new LogicalRow(row.y, row.height, anchors.size());
                for (Cell cell : row.cells) {
                    logical.values[nearestAnchor(anchors, cell.start)] = cell.text;
                }
                result.add(logical);
            }
        }
        return result;
    }

    private static boolean isContinuation(LogicalRow last, Row row, List<Float> anchors) {
        if (row.cells.size() >= anchors.size()) return false;
        float gap = Math.abs(row.y - last.lastY);
        if (gap > Math.max(10f, Math.max(last.height, row.height) * 1.55f)) return false;
        for (Cell cell : row.cells) {
            if (last.values[nearestAnchor(anchors, cell.start)] == null) return false;
        }
        return true;
    }

    private static List<Float> columnAnchors(List<Row> rows) {
        List<Float> anchors = new ArrayList<>();
        for (Row row : rows) {
            for (Cell cell : row.cells) {
                boolean exists = anchors.stream()
                        .anyMatch(anchor -> Math.abs(anchor - cell.start) <= ALIGN_TOLERANCE);
                if (!exists) anchors.add(cell.start);
            }
        }
        anchors.sort(Float::compare);
        return anchors;
    }

    private static int nearestAnchor(List<Float> anchors, float x) {
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

    private static List<Row> groupRows(List<TextPosition> glyphs) {
        List<TextPosition> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));
        List<Row> grouped = new ArrayList<>();
        for (TextPosition glyph : sorted) {
            String unicode = glyph.getUnicode();
            if (unicode == null || unicode.isEmpty()
                    || "\n".equals(unicode) || "\r".equals(unicode) || "\t".equals(unicode)) continue;
            Row last = grouped.isEmpty() ? null : grouped.get(grouped.size() - 1);
            if (last == null || Math.abs(last.y - glyph.getYDirAdj()) > ROW_TOLERANCE) {
                last = new Row(glyph.getYDirAdj(), new ArrayList<>(), new ArrayList<>());
                grouped.add(last);
            }
            last.glyphs.add(glyph);
        }
        List<Row> rows = new ArrayList<>();
        for (Row row : grouped) {
            List<Cell> cells = cells(row.glyphs);
            if (!cells.isEmpty()) {
                float height = row.glyphs.stream()
                        .map(glyph -> Math.max(glyph.getHeightDir(), glyph.getFontSizeInPt()))
                        .max(Float::compare).orElse(12f);
                rows.add(new Row(row.y, cells, height));
            }
        }
        return rows;
    }

    private static List<Row> mergeRows(List<Row> captured) {
        List<Row> rows = new ArrayList<>();
        captured.sort(Comparator.comparingDouble(row -> row.y));
        for (Row row : captured) {
            Row last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && Math.abs(last.y - row.y) <= ROW_TOLERANCE) {
                last.cells.addAll(row.cells);
                last.cells.sort(Comparator.comparingDouble(cell -> cell.start));
            } else {
                rows.add(new Row(row.y, new ArrayList<>(row.cells), row.height));
            }
        }
        return rows;
    }

    private static boolean columnsAligned(List<Row> rows) {
        int alignedRows = 0;
        for (Row row : rows) {
            int alignedCells = 0;
            for (Cell cell : row.cells) {
                boolean aligned = rows.stream().anyMatch(other -> other != row
                        && other.cells.stream().anyMatch(candidate ->
                        Math.abs(candidate.start - cell.start) <= ALIGN_TOLERANCE));
                if (aligned) alignedCells++;
            }
            if (alignedCells >= 2) alignedRows++;
        }
        return alignedRows >= 3;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Cell(String text, float start, float end) {
    }

    private static final class Row {
        private final float y;
        private final List<Cell> cells;
        private final List<TextPosition> glyphs;
        private final float height;

        private Row(float y, List<Cell> cells, float height) {
            this.y = y;
            this.cells = cells;
            this.height = height;
            this.glyphs = new ArrayList<>();
        }

        private Row(float y, List<Cell> cells, List<TextPosition> glyphs) {
            this.y = y;
            this.cells = cells;
            this.glyphs = glyphs;
            this.height = glyphs.stream()
                    .map(glyph -> Math.max(glyph.getHeightDir(), glyph.getFontSizeInPt()))
                    .max(Float::compare).orElse(12f);
        }
    }

    private static final class LogicalRow {
        private final String[] values;
        private float height;
        private float lastY;

        private LogicalRow(float y, float height, int columns) {
            this.values = new String[columns];
            this.height = height;
            this.lastY = y;
        }
    }

    private static final class Table {
        private final List<LogicalRow> rows;
        private List<Float> anchors;
        private final float firstY;
        private float lastY;
        private float pageHeight;
        private boolean continuation;
        private String caption;
        private List<String> notes = new ArrayList<>();

        private Table(List<LogicalRow> rows, List<Float> anchors, float pageHeight, boolean continuation) {
            this.rows = rows;
            this.anchors = anchors;
            this.pageHeight = pageHeight;
            this.continuation = continuation;
            this.firstY = rows.isEmpty() ? 0 : rows.get(0).lastY;
            this.lastY = rows.isEmpty() ? 0 : rows.get(rows.size() - 1).lastY;
        }
    }
}
