package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 表格布局：按页选择最佳提取器，并把相邻页同一张表合并。
 *
 * <p>优先级与既有实现一致：矩形单元格表格 &gt; 线条网格表格 &gt; 文本坐标推断。
 * 区别是按页选择，避免“文档里只要有一张矩形表格，其他页的线条网格表格就全部失效”。
 * 跨页合并只对矩形/线条表格做几何约束；文本坐标表格已在提取器内部处理跨页。</p>
 */
final class PdfTableLayout {

    private static final int MAX_PAGES = 50;

    private PdfTableLayout() {
    }

    static List<PdfTableBlock> extract(PDDocument doc) {
        Map<Integer, List<PdfTableBlock>> rect = byPage(PdfRectangleTableExtractor.extractBlocks(doc));
        Map<Integer, List<PdfTableBlock>> grid = byPage(PdfGridLineTableExtractor.extractBlocks(doc));
        Map<Integer, List<PdfTableBlock>> text = byPage(PdfTableExtractor.extractBlocks(doc));

        List<PdfTableBlock> selected = new ArrayList<>();
        int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
        for (int page = 0; page < pages; page++) {
            List<PdfTableBlock> blocks = rect.get(page);
            if (blocks == null || blocks.isEmpty()) blocks = grid.get(page);
            if (blocks == null || blocks.isEmpty()) blocks = text.get(page);
            if (blocks != null && !blocks.isEmpty()) {
                selected.addAll(blocks);
            }
        }

        selected.sort(Comparator.comparingInt(PdfTableBlock::pageFrom)
                .thenComparing(PdfTableBlock::top));
        return mergeAcrossPages(doc, selected);
    }

    private static Map<Integer, List<PdfTableBlock>> byPage(List<PdfTableBlock> blocks) {
        Map<Integer, List<PdfTableBlock>> map = new HashMap<>();
        for (PdfTableBlock block : blocks) {
            map.computeIfAbsent(block.pageFrom(), key -> new ArrayList<>()).add(block);
        }
        return map;
    }

    private static List<PdfTableBlock> mergeAcrossPages(PDDocument doc, List<PdfTableBlock> blocks) {
        List<PdfTableBlock> merged = new ArrayList<>();
        for (PdfTableBlock current : blocks) {
            PdfTableBlock previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (previous != null && canMerge(previous, current)) {
                String html = OcrTableExtractor.mergeTables(previous.html(), current.html());
                if (!html.equals(previous.html() + "\n" + current.html())) {
                    List<PdfTableBlock.PageSpan> spans = new ArrayList<>(previous.spans());
                    spans.addAll(current.spans());
                    merged.set(merged.size() - 1, new PdfTableBlock(
                            previous.pageFrom(), current.pageTo(), previous.top(), current.bottom(),
                            html, spans, true));
                    continue;
                }
            }
            merged.add(current);
        }
        return merged;
    }

    private static boolean canMerge(PdfTableBlock previous, PdfTableBlock current) {
        if (!previous.mergeable() || !current.mergeable()) return false;
        return previous.pageTo() + 1 == current.pageFrom();
    }
}
