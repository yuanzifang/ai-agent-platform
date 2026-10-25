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
        assertTrue(table.contains("colspan=\"2\">Sales"), "表头应跨两列，实际=" + table);
        assertTrue(table.contains("rowspan=\"2\">Product"), "表头首列应跨两行，实际=" + table);
        assertTrue(table.contains("rowspan=\"2\">Fruit"), "数据首列应跨两行，实际=" + table);
        assertTrue(table.contains(">Water<") && table.contains(">Apple<"), "文字应落入网格，实际=" + table);
    }
}
