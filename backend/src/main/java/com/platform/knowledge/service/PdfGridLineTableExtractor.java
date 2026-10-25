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
 * 线条绘制的 PDF 表格：收集横竖线构建网格，缺失内部线视为合并单元格。
 * 仅在线条网格和文字足够时输出，矩形单元格表格由 PdfRectangleTableExtractor 优先处理。
 */
final class PdfGridLineTableExtractor {

    private static final float TOL = 2f;

    private PdfGridLineTableExtractor() {
    }

    static String extract(PDDocument doc) {
        String merged = "";
        try {
            for (int p = 0; p < Math.min(doc.getNumberOfPages(), 50); p++) {
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
                String table = toTable(glyphs, segments(page), page.getMediaBox().getHeight());
                if (!table.isBlank()) merged = OcrTableExtractor.mergeTables(merged, table);
            }
        } catch (Exception e) {
            return "";
        }
        return merged;
    }

    private static List<Segment> segments(PDPage page) {
        Collector collector = new Collector(page);
        try {
            collector.processPage(page);
        } catch (Exception e) {
            return List.of();
        }
        return collector.segments;
    }

    private static String toTable(List<TextPosition> glyphs, List<Segment> segments, float pageHeight) {
        if (segments.size() < 4 || glyphs.isEmpty()) return "";
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        float gx0 = Float.MAX_VALUE, gx1 = -Float.MAX_VALUE, gy0 = Float.MAX_VALUE, gy1 = -Float.MAX_VALUE;
        for (TextPosition g : glyphs) {
            gx0 = Math.min(gx0, g.getXDirAdj());
            gx1 = Math.max(gx1, g.getXDirAdj());
            gy0 = Math.min(gy0, g.getYDirAdj());
            gy1 = Math.max(gy1, g.getYDirAdj());
        }
        // 以文字附近的横线定位表格外框，再纳入外框内的竖线，避免只按文字宽度截断右侧空白列。
        float yMargin = 80f;
        List<Segment> horizontals = new ArrayList<>();
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (Segment s : segments) {
            if (!s.horizontal || s.length() < 20) continue;
            if (s.fixed < gy0 - yMargin || s.fixed > gy1 + yMargin) continue;
            horizontals.add(s);
            left = Math.min(left, s.from);
            right = Math.max(right, s.to);
            top = Math.min(top, s.fixed);
            bottom = Math.max(bottom, s.fixed);
        }
        if (horizontals.size() < 2 || right - left < 20) return "";
        for (Segment s : horizontals) {
            addEdge(ys, s.fixed);
            addEdge(xs, s.from);
            addEdge(xs, s.to);
        }
        for (Segment s : segments) {
            if (s.horizontal || s.length() < 20) continue;
            if (s.fixed < left - TOL || s.fixed > right + TOL) continue;
            addEdge(xs, s.fixed);
            if (s.from >= top - TOL && s.from <= bottom + TOL) addEdge(ys, s.from);
            if (s.to >= top - TOL && s.to <= bottom + TOL) addEdge(ys, s.to);
        }
        xs.sort(Float::compare);
        ys.sort(Float::compare);
        if (xs.size() < 3 || ys.size() < 3 || xs.size() > 30 || ys.size() > 50) return "";

        int cols = xs.size() - 1;
        int rows = ys.size() - 1;
        Dsu dsu = new Dsu(rows * cols);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int node = y * cols + x;
                if (x + 1 < cols && !hasVertical(segments, xs.get(x + 1), ys.get(y), ys.get(y + 1))) {
                    dsu.union(node, y * cols + x + 1);
                }
                if (y + 1 < rows && !hasHorizontal(segments, ys.get(y + 1), xs.get(x), xs.get(x + 1))) {
                    dsu.union(node, (y + 1) * cols + x);
                }
            }
        }
        Map<Integer, Component> components = new LinkedHashMap<>();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int root = dsu.find(y * cols + x);
                int cx = x, cy = y;
                Component c = components.computeIfAbsent(root, k -> new Component(cx, cy));
                c.minX = Math.min(c.minX, x);
                c.maxX = Math.max(c.maxX, x);
                c.minY = Math.min(c.minY, y);
                c.maxY = Math.max(c.maxY, y);
            }
        }
        for (TextPosition glyph : glyphs) {
            String text = glyph.getUnicode();
            if (text == null || text.isEmpty()) continue;
            int x = edgeIndex(xs, glyph.getXDirAdj());
            int y = edgeIndex(ys, glyph.getYDirAdj());
            if (x < 0 || y < 0) continue;
            Component c = components.get(dsu.find(y * cols + x));
            if (c != null) c.text.append(text);
        }
        long withText = components.values().stream().filter(c -> !c.text.toString().trim().isEmpty()).count();
        if (withText < 2) return "";

        boolean[][] covered = new boolean[rows][cols];
        List<Component> ordered = new ArrayList<>(components.values());
        ordered.sort(Comparator.comparingInt((Component c) -> c.minY).thenComparingInt(c -> c.minX));
        StringBuilder html = new StringBuilder("<table border=\"1\">\n");
        for (int y = 0; y < rows; y++) {
            html.append("<tr>");
            for (int x = 0; x < cols; x++) {
                if (covered[y][x]) continue;
                Component c = components.get(dsu.find(y * cols + x));
                if (c == null) continue;
                int spanX = c.maxX - c.minX + 1;
                int spanY = c.maxY - c.minY + 1;
                html.append("<td");
                if (spanX > 1) html.append(" colspan=\"").append(spanX).append("\"");
                if (spanY > 1) html.append(" rowspan=\"").append(spanY).append("\"");
                html.append(">").append(escape(c.text.toString().trim())).append("</td>");
                for (int yy = c.minY; yy <= c.maxY; yy++) {
                    for (int xx = c.minX; xx <= c.maxX; xx++) covered[yy][xx] = true;
                }
            }
            html.append("</tr>\n");
        }
        return html.append("</table>").toString();
    }

    private static boolean hasVertical(List<Segment> segments, float x, float y0, float y1) {
        for (Segment s : segments) {
            if (s.horizontal || Math.abs(s.fixed - x) > TOL) continue;
            if (s.from <= y0 + TOL && s.to >= y1 - TOL) return true;
        }
        return false;
    }

    private static boolean hasHorizontal(List<Segment> segments, float y, float x0, float x1) {
        for (Segment s : segments) {
            if (!s.horizontal || Math.abs(s.fixed - y) > TOL) continue;
            if (s.from <= x0 + TOL && s.to >= x1 - TOL) return true;
        }
        return false;
    }

    private static void addEdge(List<Float> values, float v) {
        if (values.stream().noneMatch(x -> Math.abs(x - v) <= TOL)) values.add(v);
    }

    private static int edgeIndex(List<Float> edges, float value) {
        for (int i = 0; i + 1 < edges.size(); i++) {
            if (value >= edges.get(i) - TOL && value <= edges.get(i + 1) + TOL) return i;
        }
        return -1;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Segment(boolean horizontal, float fixed, float from, float to) {
        private float length() {
            return Math.abs(to - from);
        }
    }

    private static final class Component {
        private int minX, maxX, minY, maxY;
        private final StringBuilder text = new StringBuilder();

        private Component(int x, int y) {
            minX = maxX = x;
            minY = maxY = y;
        }
    }

    private static final class Dsu {
        private final int[] parent;

        private Dsu(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        private int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        private void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra != rb) parent[rb] = ra;
        }
    }

    private static final class Collector extends PDFGraphicsStreamEngine {
        private final List<Segment> segments = new ArrayList<>();
        private final List<Segment> pending = new ArrayList<>();
        private final float pageHeight;
        private Point2D current;
        private Point2D start;

        private Collector(PDPage page) {
            super(page);
            this.pageHeight = page.getMediaBox().getHeight();
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            add(p0, p1);
            add(p1, p2);
            add(p2, p3);
            add(p3, p0);
        }

        private void add(Point2D a, Point2D b) {
            float ax = (float) a.getX(), ay = (float) a.getY();
            float bx = (float) b.getX(), by = (float) b.getY();
            if (Math.abs(ax - bx) <= TOL) {
                float from = pageHeight - Math.max(ay, by);
                float to = pageHeight - Math.min(ay, by);
                pending.add(new Segment(false, ax, from, to));
            } else if (Math.abs(ay - by) <= TOL) {
                pending.add(new Segment(true, pageHeight - ay, Math.min(ax, bx), Math.max(ax, bx)));
            }
        }

        @Override public void moveTo(float x, float y) {
            current = new Point2D.Float(x, y);
            start = current;
        }
        @Override public void lineTo(float x, float y) {
            Point2D next = new Point2D.Float(x, y);
            if (current != null) add(current, next);
            current = next;
        }
        @Override public void closePath() {
            if (current != null && start != null) add(current, start);
            current = start;
        }
        @Override public void strokePath() {
            segments.addAll(pending);
            pending.clear();
            current = null;
            start = null;
        }
        @Override public void fillPath(int windingRule) {
            pending.clear();
            current = null;
            start = null;
        }
        @Override public void fillAndStrokePath(int windingRule) {
            segments.addAll(pending);
            pending.clear();
            current = null;
            start = null;
        }
        @Override public void endPath() {
            pending.clear();
            current = null;
            start = null;
        }
        @Override public Point2D getCurrentPoint() { return current == null ? new Point2D.Float(0, 0) : current; }
        @Override public void clip(int windingRule) { }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
        @Override public void drawImage(PDImage image) { }
    }
}
