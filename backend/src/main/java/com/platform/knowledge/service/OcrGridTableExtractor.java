package com.platform.knowledge.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描件带网格表格：从页面位图检测横竖线，构建网格，缺失内部线视为合并单元格。
 * 仅当检测结果至少包含一个 colspan/rowspan 时返回，简单表格仍走 TSV 文本重建。
 */
final class OcrGridTableExtractor {

    private static final float TOL = 3f;
    private static final int DARK_LIMIT = 180;
    private static final int MIN_LINE = 60;
    private static final int MAX_GAP = 2;
    private static final double MIN_DENSITY = 0.75;

    private OcrGridTableExtractor() {
    }

    record Box(String text, float left, float top, float width, float height) {
    }

    static String extract(BufferedImage image, List<Box> words) {
        if (image == null || words == null || words.isEmpty()) return "";
        List<Segment> segments = detectLines(image);
        if (segments.size() < 4) return "";

        float gx0 = Float.MAX_VALUE, gx1 = -Float.MAX_VALUE;
        float gy0 = Float.MAX_VALUE, gy1 = -Float.MAX_VALUE;
        for (Box box : words) {
            gx0 = Math.min(gx0, box.left);
            gx1 = Math.max(gx1, box.left + box.width);
            gy0 = Math.min(gy0, box.top);
            gy1 = Math.max(gy1, box.top + box.height);
        }

        // 先找文字附近的横线作为表格外框，再把外框内竖线纳入，避免右侧空白列被截断。
        float yMargin = 100f;
        List<Segment> horizontals = new ArrayList<>();
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (Segment segment : segments) {
            if (!segment.horizontal || segment.length() < MIN_LINE) continue;
            if (segment.fixed < gy0 - yMargin || segment.fixed > gy1 + yMargin) continue;
            horizontals.add(segment);
            left = Math.min(left, segment.from);
            right = Math.max(right, segment.to);
            top = Math.min(top, segment.fixed);
            bottom = Math.max(bottom, segment.fixed);
        }
        if (horizontals.size() < 2 || right - left < MIN_LINE) return "";

        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        for (Segment segment : horizontals) {
            addEdge(ys, segment.fixed);
            addEdge(xs, segment.from);
            addEdge(xs, segment.to);
        }
        for (Segment segment : segments) {
            if (segment.horizontal || segment.length() < MIN_LINE) continue;
            if (segment.fixed < left - TOL || segment.fixed > right + TOL) continue;
            addEdge(xs, segment.fixed);
            if (segment.from >= top - TOL && segment.from <= bottom + TOL) addEdge(ys, segment.from);
            if (segment.to >= top - TOL && segment.to <= bottom + TOL) addEdge(ys, segment.to);
        }
        xs.sort(Float::compare);
        ys.sort(Float::compare);
        if (xs.size() < 3 || ys.size() < 3 || xs.size() > 30 || ys.size() > 60) return "";

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
                Component component = components.computeIfAbsent(root, key -> new Component(cx, cy));
                component.minX = Math.min(component.minX, x);
                component.maxX = Math.max(component.maxX, x);
                component.minY = Math.min(component.minY, y);
                component.maxY = Math.max(component.maxY, y);
            }
        }
        for (Box word : words) {
            if (word.text == null || word.text.isBlank()) continue;
            int x = edgeIndex(xs, word.left + word.width / 2f);
            int y = edgeIndex(ys, word.top + word.height / 2f);
            if (x < 0 || y < 0) continue;
            Component component = components.get(dsu.find(y * cols + x));
            if (component == null) continue;
            if (!component.text.isEmpty()) component.text.append(' ');
            component.text.append(word.text.trim());
        }

        long withText = components.values().stream().filter(c -> !c.text.toString().isBlank()).count();
        if (withText < 2) return "";
        boolean hasSpan = components.values().stream()
                .anyMatch(c -> c.maxX > c.minX || c.maxY > c.minY);
        if (!hasSpan) return "";

        int headerRows = headerRows(components, dsu, rows, cols);
        boolean[][] covered = new boolean[rows][cols];
        StringBuilder html = new StringBuilder("<table border=\"1\">\n");
        if (headerRows > 0) html.append("<thead>\n");
        else html.append("<tbody>\n");
        for (int y = 0; y < rows; y++) {
            if (headerRows > 0 && y == headerRows) html.append("</thead>\n<tbody>\n");
            html.append("<tr>");
            for (int x = 0; x < cols; x++) {
                if (covered[y][x]) continue;
                Component component = components.get(dsu.find(y * cols + x));
                if (component == null) continue;
                int spanX = component.maxX - component.minX + 1;
                int spanY = component.maxY - component.minY + 1;
                boolean header = y < headerRows && component.maxY < headerRows;
                String tag = header ? "th" : "td";
                html.append("<").append(tag);
                if (spanX > 1) html.append(" colspan=\"").append(spanX).append("\"");
                if (spanY > 1) html.append(" rowspan=\"").append(spanY).append("\"");
                html.append(">").append(escape(component.text.toString().trim()))
                        .append("</").append(tag).append(">");
                for (int yy = component.minY; yy <= component.maxY; yy++) {
                    for (int xx = component.minX; xx <= component.maxX; xx++) covered[yy][xx] = true;
                }
            }
            html.append("</tr>\n");
        }
        return html.append("</tbody>\n</table>").toString();
    }

    private static int headerRows(Map<Integer, Component> components, Dsu dsu, int rows, int cols) {
        if (rows < 2) return 0;
        Row first = rowInfo(components, dsu, 0, cols);
        if (!first.hasText || first.hasNumber) return 0;
        boolean firstSpan = components.values().stream()
                .anyMatch(c -> c.minY == 0 && (c.maxX > c.minX || c.maxY > c.minY));
        int headers = 1;
        if (rows > 2) {
            Row second = rowInfo(components, dsu, 1, cols);
            if (second.hasText && !second.hasNumber && (second.allYears || firstSpan)) headers = 2;
            if (headers == 2 && rows > 3) {
                Row third = rowInfo(components, dsu, 2, cols);
                if (third.hasText && !third.hasNumber && third.allYears) headers = 3;
            }
        }
        return Math.min(headers, rows - 1);
    }

    private static Row rowInfo(Map<Integer, Component> components, Dsu dsu, int row, int cols) {
        boolean hasText = false, hasNumber = false, allYears = true;
        for (int x = 0; x < cols; x++) {
            Component c = components.get(dsu.find(row * cols + x));
            if (c == null || c.minY != row) continue;
            String text = c.text.toString().trim();
            if (text.isEmpty()) continue;
            hasText = true;
            boolean year = isYear(text);
            if (!year) allYears = false;
            if (!year && text.matches("[-+]?\\d+([.,]\\d+)?%?")) hasNumber = true;
        }
        return new Row(hasText, hasNumber, allYears);
    }

    private static boolean isYear(String text) {
        return text.matches("(19|20)\\d{2}(年)?");
    }

    private record Row(boolean hasText, boolean hasNumber, boolean allYears) {
    }

    private static List<Segment> detectLines(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        List<Segment> result = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            int start = -1, last = -1, darkCount = 0;
            for (int x = 0; x < width; x++) {
                if (dark(image.getRGB(x, y))) {
                    if (start < 0) start = x;
                    last = x;
                    darkCount++;
                } else if (start >= 0 && x - last > MAX_GAP) {
                    addRun(result, true, y, start, last, darkCount);
                    start = -1;
                    last = -1;
                    darkCount = 0;
                }
            }
            if (start >= 0) addRun(result, true, y, start, last, darkCount);
        }
        for (int x = 0; x < width; x++) {
            int start = -1, last = -1, darkCount = 0;
            for (int y = 0; y < height; y++) {
                if (dark(image.getRGB(x, y))) {
                    if (start < 0) start = y;
                    last = y;
                    darkCount++;
                } else if (start >= 0 && y - last > MAX_GAP) {
                    addRun(result, false, x, start, last, darkCount);
                    start = -1;
                    last = -1;
                    darkCount = 0;
                }
            }
            if (start >= 0) addRun(result, false, x, start, last, darkCount);
        }
        return result;
    }

    private static void addRun(List<Segment> result, boolean horizontal, int fixed,
                              int start, int end, int darkCount) {
        int length = end - start + 1;
        if (length >= MIN_LINE && darkCount >= length * MIN_DENSITY) {
            result.add(new Segment(horizontal, fixed, start, end));
        }
    }

    private static boolean dark(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000 < DARK_LIMIT;
    }

    private static boolean hasVertical(List<Segment> segments, float x, float y0, float y1) {
        for (Segment segment : segments) {
            if (segment.horizontal || Math.abs(segment.fixed - x) > TOL) continue;
            if (segment.from <= y0 + TOL && segment.to >= y1 - TOL) return true;
        }
        return false;
    }

    private static boolean hasHorizontal(List<Segment> segments, float y, float x0, float x1) {
        for (Segment segment : segments) {
            if (!segment.horizontal || Math.abs(segment.fixed - y) > TOL) continue;
            if (segment.from <= x0 + TOL && segment.to >= x1 - TOL) return true;
        }
        return false;
    }

    private static void addEdge(List<Float> edges, float value) {
        if (edges.stream().noneMatch(edge -> Math.abs(edge - value) <= TOL)) edges.add(value);
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

        private Dsu(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) parent[i] = i;
        }

        private int find(int value) {
            while (parent[value] != value) {
                parent[value] = parent[parent[value]];
                value = parent[value];
            }
            return value;
        }

        private void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra != rb) parent[rb] = ra;
        }
    }
}
