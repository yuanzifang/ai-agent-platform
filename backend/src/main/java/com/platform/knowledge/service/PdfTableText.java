package com.platform.knowledge.service;

import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** PDF 表格附近文本：标题/脚注行重建。 */
final class PdfTableText {

    private static final float LINE_TOLERANCE = 3f;

    private PdfTableText() {
    }

    static String caption(List<TextPosition> glyphs, float tableTop) {
        String best = null;
        for (Line line : lines(glyphs)) {
            if (line.top >= tableTop - 2 || line.top < tableTop - 120) continue;
            String text = line.text.trim();
            if (text.length() > 200 || text.isBlank()) continue;
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("table") || text.contains("表")) best = text;
        }
        return best;
    }

    static List<String> notes(List<TextPosition> glyphs, float tableBottom) {
        List<String> notes = new ArrayList<>();
        for (Line line : lines(glyphs)) {
            if (line.top <= tableBottom + 2 || line.top > tableBottom + 200) continue;
            String text = line.text.trim();
            if (text.isBlank() || text.length() > 300) continue;
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("note") || lower.startsWith("source") || text.startsWith("注")
                    || text.startsWith("来源") || text.startsWith("*") || text.startsWith("†")) {
                notes.add(text);
                if (notes.size() >= 3) break;
            }
        }
        return notes;
    }

    private static List<Line> lines(List<TextPosition> glyphs) {
        List<TextPosition> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));
        List<Line> lines = new ArrayList<>();
        Line current = null;
        for (TextPosition glyph : sorted) {
            if (current == null || Math.abs(current.top - glyph.getYDirAdj()) > LINE_TOLERANCE) {
                current = new Line(glyph.getYDirAdj());
                lines.add(current);
            }
            current.glyphs.add(glyph);
        }
        for (Line line : lines) line.build();
        return lines;
    }

    private static final class Line {
        private final float top;
        private final List<TextPosition> glyphs = new ArrayList<>();
        private String text = "";

        private Line(float top) {
            this.top = top;
        }

        private void build() {
            glyphs.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
            StringBuilder sb = new StringBuilder();
            float end = -1;
            for (TextPosition glyph : glyphs) {
                String value = glyph.getUnicode();
                if (value == null) continue;
                if (end >= 0 && glyph.getXDirAdj() - end > 4) sb.append(' ');
                sb.append(value);
                end = glyph.getXDirAdj() + glyph.getWidthDirAdj();
            }
            text = sb.toString();
        }
    }
}
