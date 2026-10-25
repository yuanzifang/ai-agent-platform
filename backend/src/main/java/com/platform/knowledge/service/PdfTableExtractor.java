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

        StringBuilder table = new StringBuilder("<table border=\"1\">\n");
        for (Row row : candidates) {
            table.append("<tr>");
            for (Cell cell : row.cells) {
                table.append("<td>").append(escape(cell.text)).append("</td>");
            }
            table.append("</tr>\n");
        }
        return table.append("</table>").toString();
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
            if (cells.size() >= 2) rows.add(new Row(row.y, cells));
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
                rows.add(new Row(row.y, new ArrayList<>(row.cells)));
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

        private Row(float y, List<Cell> cells) {
            this.y = y;
            this.cells = cells;
            this.glyphs = new ArrayList<>();
        }

        private Row(float y, List<Cell> cells, List<TextPosition> glyphs) {
            this.y = y;
            this.cells = cells;
            this.glyphs = glyphs;
        }
    }
}
