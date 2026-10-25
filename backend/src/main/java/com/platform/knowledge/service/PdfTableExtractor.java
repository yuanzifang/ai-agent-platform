package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PDF 表格轻量重建：按文本坐标把同一行的单元格拼成 HTML 表格。
 *
 * <p>只处理带文本层的 PDF；扫描件仍走 OCR/图片链路。为避免把普通正文误判为表格，
 * 仅当一页出现至少 3 行、最多列数至少 3、且列起始位置跨行对齐时才输出。</p>
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
        StringBuilder tables = new StringBuilder();
        try {
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int page = 1; page <= pages; page++) {
                List<TextPosition> glyphs = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        glyphs.add(text);
                        super.processTextPosition(text);
                    }
                };
                stripper.setSortByPosition(true);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.getText(doc);
                String table = toTable(groupRows(glyphs));
                if (!table.isEmpty()) {
                    tables.append('\n').append(table).append('\n');
                }
            }
        } catch (Exception e) {
            return "";
        }
        return tables.toString();
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

    private static String toTable(List<Row> captured) {
        List<Row> rows = mergeRows(captured);
        List<Row> candidates = rows.stream().filter(row -> row.cells.size() >= 2).toList();
        if (candidates.size() < 3) return "";
        int maxColumns = candidates.stream().mapToInt(row -> row.cells.size()).max().orElse(0);
        if (maxColumns < 3 || !columnsAligned(candidates)) return "";
        List<Float> anchors = columnAnchors(candidates);
        if (anchors.size() < 3 || anchors.size() > 30) return "";
        float minY = candidates.stream().map(row -> row.y).min(Float::compare).orElse(0f);
        float maxY = candidates.stream().map(row -> row.y).max(Float::compare).orElse(0f);
        float maxHeight = candidates.stream().map(row -> row.height).max(Float::compare).orElse(12f);
        List<LogicalRow> logicalRows = logicalRows(rows, anchors, minY, maxY + Math.max(12f, maxHeight * 1.8f));
        if (logicalRows.size() < 3) return "";

        StringBuilder table = new StringBuilder("<table border=\"1\">\n");
        for (LogicalRow row : logicalRows) {
            table.append("<tr>");
            for (String cell : row.values) {
                table.append("<td>").append(cell == null ? "" : escape(cell)).append("</td>");
            }
            table.append("</tr>\n");
        }
        return table.append("</table>").toString();
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

    /** 列锚点：把跨行接近的单元格起始 X 聚类，空单元格也保留列位。 */
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
}
