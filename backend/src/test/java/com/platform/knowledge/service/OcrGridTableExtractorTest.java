package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrGridTableExtractorTest {

    @Test
    void restoresHeaderColspanAndDataRowspan() {
        BufferedImage image = new BufferedImage(400, 340, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 340);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            for (int x : new int[]{20, 150, 380}) g.drawLine(x, 20, x, 320);
            g.drawLine(280, 80, 280, 320);
            g.drawLine(20, 20, 380, 20);
            g.drawLine(150, 80, 380, 80);
            g.drawLine(20, 140, 380, 140);
            g.drawLine(20, 200, 380, 200);
            g.drawLine(150, 260, 380, 260);
            g.drawLine(20, 320, 380, 320);
        } finally {
            g.dispose();
        }

        List<OcrGridTableExtractor.Box> boxes = List.of(
                new OcrGridTableExtractor.Box("Product", 30, 40, 80, 20),
                new OcrGridTableExtractor.Box("Sales", 160, 40, 100, 20),
                new OcrGridTableExtractor.Box("2024", 160, 100, 70, 20),
                new OcrGridTableExtractor.Box("2025", 290, 100, 70, 20),
                new OcrGridTableExtractor.Box("Water", 30, 160, 70, 20),
                new OcrGridTableExtractor.Box("10", 160, 160, 30, 20),
                new OcrGridTableExtractor.Box("20", 290, 160, 30, 20),
                new OcrGridTableExtractor.Box("Fruit", 30, 220, 60, 20),
                new OcrGridTableExtractor.Box("Apple", 160, 220, 70, 20),
                new OcrGridTableExtractor.Box("5", 290, 220, 20, 20),
                new OcrGridTableExtractor.Box("Pear", 160, 290, 60, 20),
                new OcrGridTableExtractor.Box("6", 290, 290, 20, 20));

        String table = OcrGridTableExtractor.extract(image, boxes);
        assertTrue(table.contains("<thead>") && table.contains("<tbody>"), "应输出表头/表体，实际=" + table);
        assertTrue(table.contains("<th colspan=\"2\">Sales"), "表头应跨两列，实际=" + table);
        assertTrue(table.contains("<th rowspan=\"2\">Product"), "表头首列应跨两行，实际=" + table);
        assertTrue(table.contains("<th>2024</th>"), "两级年份表头应保留，实际=" + table);
        assertTrue(table.contains("rowspan=\"2\">Fruit"), "数据首列应跨两行，实际=" + table);
        assertTrue(table.contains(">Water<") && table.contains(">Apple<"), "文字应落入网格，实际=" + table);
    }

    @Test
    void numericFirstRowDoesNotOpenThead() {
        BufferedImage image = new BufferedImage(300, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 300, 160);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(20, 20, 20, 140);
            g.drawLine(280, 20, 280, 140);
            g.drawLine(150, 80, 150, 140);
            g.drawLine(20, 20, 280, 20);
            g.drawLine(20, 80, 280, 80);
            g.drawLine(20, 140, 280, 140);
        } finally {
            g.dispose();
        }
        List<OcrGridTableExtractor.Box> boxes = List.of(
                new OcrGridTableExtractor.Box("1", 30, 40, 20, 20),
                new OcrGridTableExtractor.Box("3", 30, 100, 20, 20),
                new OcrGridTableExtractor.Box("4", 160, 100, 20, 20));

        String table = OcrGridTableExtractor.extract(image, boxes);
        assertTrue(table.contains("<tbody>") && !table.contains("<thead>"),
                "数字首行不应输出 thead，实际=" + table);
        assertTrue(table.contains("colspan=\"2\"") && table.contains(">1<"),
                "数字首行合并单元格仍应保留，实际=" + table);
    }

    @Test
    void restoresDashedGridTableAsFallback() {
        BufferedImage image = new BufferedImage(400, 340, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 340);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            for (int x : new int[]{20, 150, 380}) drawDashed(g, x, 20, x, 320);
            for (int y : new int[]{20, 80, 140, 200, 320}) drawDashed(g, 20, y, 380, y);
            drawDashed(g, 280, 80, 280, 320);
        } finally {
            g.dispose();
        }
        List<OcrGridTableExtractor.Box> boxes = List.of(
                new OcrGridTableExtractor.Box("Product", 30, 40, 80, 20),
                new OcrGridTableExtractor.Box("Sales", 160, 40, 100, 20),
                new OcrGridTableExtractor.Box("2024", 160, 100, 70, 20),
                new OcrGridTableExtractor.Box("2025", 290, 100, 70, 20),
                new OcrGridTableExtractor.Box("Water", 30, 160, 70, 20),
                new OcrGridTableExtractor.Box("10", 160, 160, 30, 20),
                new OcrGridTableExtractor.Box("20", 290, 160, 30, 20));

        String table = OcrGridTableExtractor.extract(image, boxes);
        assertTrue(table.contains("<thead>") && table.contains("colspan=\"2\">Sales"),
                "虚线网格表头应可还原，实际=" + table);
        assertTrue(table.contains("Water"), "虚线网格文字应保留，实际=" + table);
    }

    private static void drawDashed(Graphics2D g, int x1, int y1, int x2, int y2) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        double dx = (x2 - x1) / length;
        double dy = (y2 - y1) / length;
        for (double start = 0; start < length; start += 12) {
            double end = Math.min(start + 8, length);
            g.drawLine((int) (x1 + dx * start), (int) (y1 + dy * start),
                    (int) (x1 + dx * end), (int) (y1 + dy * end));
        }
    }

    @Test
    void restoresDottedGridTableAsFallback() {
        BufferedImage image = new BufferedImage(400, 340, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 340);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2f));
            for (int x : new int[]{20, 150, 380}) drawDotted(g, x, 20, x, 320);
            for (int y : new int[]{20, 80, 140, 200, 320}) drawDotted(g, 20, y, 380, y);
            drawDotted(g, 280, 80, 280, 320);
        } finally {
            g.dispose();
        }
        List<OcrGridTableExtractor.Box> boxes = List.of(
                new OcrGridTableExtractor.Box("Product", 30, 40, 80, 20),
                new OcrGridTableExtractor.Box("Sales", 160, 40, 100, 20),
                new OcrGridTableExtractor.Box("2024", 160, 100, 70, 20),
                new OcrGridTableExtractor.Box("2025", 290, 100, 70, 20),
                new OcrGridTableExtractor.Box("Water", 30, 160, 70, 20),
                new OcrGridTableExtractor.Box("10", 160, 160, 30, 20),
                new OcrGridTableExtractor.Box("20", 290, 160, 30, 20));

        String table = OcrGridTableExtractor.extract(image, boxes);
        assertTrue(table.contains("<thead>") && table.contains("colspan=\"2\">Sales"),
                "点线网格表头应可还原，实际=" + table);
        assertTrue(table.contains("Water"), "点线网格文字应保留，实际=" + table);
    }

    private static void drawDotted(Graphics2D g, int x1, int y1, int x2, int y2) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        double dx = (x2 - x1) / length;
        double dy = (y2 - y1) / length;
        for (double start = 0; start < length; start += 6) {
            double end = Math.min(start + 2, length);
            g.drawLine((int) (x1 + dx * start), (int) (y1 + dy * start),
                    (int) (x1 + dx * end), (int) (y1 + dy * end));
        }
        g.drawLine((int) (x1 + dx * Math.max(0, length - 2)), (int) (y1 + dy * Math.max(0, length - 2)), x2, y2);
    }
}
