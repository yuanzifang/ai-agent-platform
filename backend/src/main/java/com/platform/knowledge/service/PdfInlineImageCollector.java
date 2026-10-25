package com.platform.knowledge.service;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 收集 PDF 内容流里的内联图片（BI...ID...EI）；XObject 仍由原有逻辑处理。 */
final class PdfInlineImageCollector extends PDFGraphicsStreamEngine {

    private final List<byte[]> images = new ArrayList<>();

    PdfInlineImageCollector(PDPage page) {
        super(page);
    }

    List<byte[]> images() {
        return images;
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
        if (!(image instanceof PDInlineImage inline)) return;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(inline.getImage(), "png", out);
            if (out.size() > 0 && out.size() <= 10 * 1024 * 1024) images.add(out.toByteArray());
        } catch (Exception e) {
            // 单张内联图失败不影响其他图片
        }
    }

    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
    @Override public void clip(int windingRule) { }
    @Override public void moveTo(float x, float y) { }
    @Override public void lineTo(float x, float y) { }
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
    @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
    @Override public void closePath() { }
    @Override public void endPath() { }
    @Override public void strokePath() { }
    @Override public void fillPath(int windingRule) { }
    @Override public void fillAndStrokePath(int windingRule) { }
    @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
}
