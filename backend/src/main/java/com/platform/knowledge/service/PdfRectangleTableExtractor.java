package com.platform.knowledge.service;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带边框 PDF 表格：从 addRect 单元格矩形还原行列和 colspan/rowspan。
 * 仅处理显式矩形单元格；线条绘制的网格仍由文本坐标提取器兜底。
 */
final class PdfRectangleTableExtractor {

    private static final float TOLERANCE = 2f;

    private PdfRectangleTableExtractor() {
    }

    static String extract(PDDocument doc) {
        String merged = "";
        try {
            int pages = Math.min(doc.getNumberOfPages(), 50);
            for (int p = 0; p < pages; p++) {
                PDPage page = doc.getPage(p);
                List<TextPosition> glyphs = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        glyphs.add(text);
                        super.processTextPosition(text);
                    }
                };
                stripper.setSortByPosition(true);
                stripper.setStartPage(p + 1);
                stripper.setEndPage(p + 1);
                stripper.getText(doc);
                String table = toTable(glyphs, rectangles(page), page.getMediaBox().getHeight());
                if (!table.isBlank()) merged = OcrTableExtractor.mergeTables(merged, table);
            }
        } catch (Exception e) {
            return "";
        }
        return merged;
    }

    private static List<Rect> rectangles(PDPage page) {
        Collector collector = new Collector(page);
        try {
            collector.processPage(page);
        } catch (Exception e) {
            return List.of();
        }
        return collector.rects;
    }

    private static String toTable(List<TextPosition> glyphs, List<Rect> source, float pageHeight) {
        if (source.size() < 4) return "";
        List<Rect> rects = new ArrayList<>();
        for (Rect r : source) {
            boolean duplicate = rects.stream().anyMatch(x -> x.near(r));
            if (!duplicate) rects.add(r);
        }
        List<Float> xs = edges(rects, true);
        List<Float> ys = edges(rects, false);
        if (xs.size() < 3 || ys.size() < 3 || xs.size() > 30 || ys.size() > 50) return "";

        Map<String, Cell> cells = new LinkedHashMap<>();
        for (Rect r : rects) {
            int x0 = index(xs, r.minX), x1 = index(xs, r.maxX);
            int y0 = index(ys, r.top), y1 = index(ys, r.bottom);
            if (x1 <= x0 || y1 <= y0) continue;
            String key = x0 + ":" + y0;
            Cell cell = cells.computeIfAbsent(key, k -> new Cell(x0, y0, x1, y1));
            cell.x1 = Math.max(cell.x1, x1);
            cell.y1 = Math.max(cell.y1, y1);
        }
        if (cells.isEmpty()) return "";

        for (TextPosition glyph : glyphs) {
            String text = glyph.getUnicode();
            if (text == null || text.isEmpty()) continue;
            float x = glyph.getXDirAdj();
            float y = glyph.getYDirAdj();
            for (Cell cell : cells.values()) {
                if (x < xs.get(cell.x0) - TOLERANCE || x > xs.get(cell.x1) + TOLERANCE) continue;
                if (y < ys.get(cell.y0) - TOLERANCE || y > ys.get(cell.y1) + TOLERANCE) continue;
                cell.text.append(text);
                break;
            }
        }

        int rows = ys.size() - 1;
        int cols = xs.size() - 1;
        boolean[][] covered = new boolean[rows][cols];
        int withText = 0;
        StringBuilder table = new StringBuilder("<table border=\"1\">\n");
        for (int y = 0; y < rows; y++) {
            table.append("<tr>");
            for (int x = 0; x < cols; x++) {
                if (covered[y][x]) continue;
                Cell cell = cells.get(x + ":" + y);
                int xspan = 1, yspan = 1;
                String text = "";
                if (cell != null) {
                    xspan = Math.max(1, cell.x1 - cell.x0);
                    yspan = Math.max(1, cell.y1 - cell.y0);
                    text = cell.text.toString().trim();
                    if (!text.isEmpty()) withText++;
                    for (int yy = y; yy < Math.min(rows, y + yspan); yy++) {
                        for (int xx = x; xx < Math.min(cols, x + xspan); xx++) covered[yy][xx] = true;
                    }
                }
                table.append("<td");
                if (xspan > 1) table.append(" colspan=\"").append(xspan).append("\"");
                if (yspan > 1) table.append(" rowspan=\"").append(yspan).append("\"");
                table.append(">").append(escape(text)).append("</td>");
            }
            table.append("</tr>\n");
        }
        if (withText < 2) return "";
        return table.append("</table>").toString();
    }

    private static List<Float> edges(List<Rect> rects, boolean horizontal) {
        List<Float> values = new ArrayList<>();
        for (Rect r : rects) {
            addEdge(values, horizontal ? r.minX : r.top);
            addEdge(values, horizontal ? r.maxX : r.bottom);
        }
        values.sort(Float::compare);
        return values;
    }

    private static void addEdge(List<Float> values, float value) {
        if (values.stream().noneMatch(v -> Math.abs(v - value) <= TOLERANCE)) values.add(value);
    }

    private static int index(List<Float> values, float value) {
        int best = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < values.size(); i++) {
            float d = Math.abs(values.get(i) - value);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class Cell {
        private final int x0, y0;
        private int x1, y1;
        private final StringBuilder text = new StringBuilder();

        private Cell(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    private record Rect(float minX, float minY, float maxX, float maxY, float top, float bottom) {
        private boolean near(Rect other) {
            return Math.abs(minX - other.minX) <= TOLERANCE && Math.abs(minY - other.minY) <= TOLERANCE
                    && Math.abs(maxX - other.maxX) <= TOLERANCE && Math.abs(maxY - other.maxY) <= TOLERANCE;
        }
    }

    private static final class Collector extends PDFGraphicsStreamEngine {
        private final List<Rect> rects = new ArrayList<>();
        private final float pageHeight;
        private List<Point2D> current;

        private Collector(PDPage page) {
            super(page);
            this.pageHeight = page.getMediaBox().getHeight();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            current = List.of(p0, p1, p2, p3);
        }

        private void commit() {
            if (current == null) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Point2D point : current) {
                minX = Math.min(minX, (float) point.getX());
                minY = Math.min(minY, (float) point.getY());
                maxX = Math.max(maxX, (float) point.getX());
                maxY = Math.max(maxY, (float) point.getY());
            }
            if (maxX - minX > 4 && maxY - minY > 4) {
                rects.add(new Rect(minX, minY, maxX, maxY, pageHeight - maxY, pageHeight - minY));
            }
            current = null;
        }

        @Override public void strokePath() { commit(); }
        @Override public void fillPath(int windingRule) { commit(); }
        @Override public void fillAndStrokePath(int windingRule) { commit(); }
        @Override public void endPath() { current = null; }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { }
        @Override public void lineTo(float x, float y) { }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
        @Override public void closePath() { }
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
        @Override public void drawImage(PDImage image) { }
    }
}
