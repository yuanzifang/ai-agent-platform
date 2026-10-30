package com.platform.knowledge.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PDF 表格布局：按页选择最佳提取器，并把相邻页同一张表合并。
 *
 * <p>优先级与既有实现一致：矩形单元格表格 &gt; 线条网格表格 &gt; 文本坐标推断。
 * 区别是按页选择，避免“文档里只要有一张矩形表格，其他页的线条网格表格就全部失效”。
 * 跨页合并只对矩形/线条表格做几何约束；文本坐标表格已在提取器内部处理跨页。</p>
 */
final class PdfTableLayout {

    private static final int MAX_PAGES = 1000;

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

        selected.removeIf(block -> !isUsefulBlock(block));
        selected = dedupeOverlapping(selected);
        selected.sort(Comparator.comparingInt(PdfTableBlock::pageFrom)
                .thenComparing(PdfTableBlock::top));
        return mergeAcrossPages(doc, selected);
    }

    /**
     * 表格质量兜底：几何提取器也会把封面、目录、签名页、长段正文框成表格。
     * 这里按 caption/单元格密度/长文本比例做统一过滤，不依赖具体提取器。
     */
    static boolean isUsefulBlock(PdfTableBlock block) {
        String html = block.html();
        if (html == null || html.isBlank()) return false;
        org.jsoup.nodes.Document doc;
        try {
            doc = Jsoup.parseBodyFragment(html);
        } catch (Exception e) {
            return true;
        }
        Element table = doc.selectFirst("table");
        if (table == null) return false;
        Element captionEl = doc.selectFirst("caption");
        String caption = captionEl == null ? "" : captionEl.text().trim();
        String normalizedCaption = caption.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "").replace("：", "");
        if (normalizedCaption.startsWith("tableofcontents")
                || normalizedCaption.equals("contents") || normalizedCaption.startsWith("目录")) {
            return false;
        }
        String text = doc.text();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (block.pageFrom() == 0 && (normalized.contains("securities and exchange commission")
                || normalized.contains("form 10-k"))) {
            return false;
        }
        if (normalized.matches("(?s).*\\b(signature|signatures)\\b.*")
                || normalized.contains("/s/")) {
            return false;
        }
        var cells = doc.select("td,th");
        if (cells.size() < 4) return false;
        int nonEmpty = 0;
        int numeric = 0;
        int longCells = 0;
        long textChars = 0;
        for (Element cell : cells) {
            String value = cell.text().trim();
            if (value.isEmpty()) continue;
            nonEmpty++;
            if (value.matches(".*\\d.*")) numeric++;
            if (value.length() > 60) longCells++;
            textChars += value.length();
        }
        if (nonEmpty < 3) return false;
        double avg = (double) textChars / nonEmpty;
        // SEC 10-K 封面/地址块：列多、平均文本长，不是数据表。
        if (block.pageFrom() == 0 && cells.size() > 50 && avg > 30) {
            return false;
        }
        // 大量空白 + 极短数字，常见于折线图/坐标轴/公式框，不是可检索表格。
        if (cells.size() >= 30 && nonEmpty < cells.size() * 0.25
                && avg < 10 && numeric < cells.size() * 0.25) {
            return false;
        }
        // 极少非空单元格的长文本：签名页、图注、正文框。
        if (nonEmpty <= 12 && avg > 25 && numeric < nonEmpty * 0.30) {
            return false;
        }
        // 参考文献、债务条款、封面地址等长文本“表格”。
        if (avg > 50 && longCells >= 3) {
            return false;
        }
        // 无标题且几乎全是长文本，通常是多栏正文被误识别。
        if (caption.isBlank() && longCells >= Math.max(3, nonEmpty / 3)
                && numeric < nonEmpty * 0.10) {
            return false;
        }
        return true;
    }

    /** 同一页上重叠且内容高度相似的表块只保留一份，避免文本抽取器重复捕获。 */
    private static List<PdfTableBlock> dedupeOverlapping(List<PdfTableBlock> blocks) {
        List<PdfTableBlock> result = new ArrayList<>();
        for (PdfTableBlock current : blocks) {
            PdfTableBlock duplicate = null;
            for (PdfTableBlock existing : result) {
                if (existing.pageFrom() != current.pageFrom()) continue;
                if (!overlaps(existing, current)) continue;
                if (similar(existing.html(), current.html())) {
                    duplicate = existing;
                    break;
                }
            }
            if (duplicate == null) {
                result.add(current);
            } else if (current.html().length() > duplicate.html().length()) {
                result.set(result.indexOf(duplicate), current);
            }
        }
        return result;
    }

    private static boolean overlaps(PdfTableBlock a, PdfTableBlock b) {
        for (PdfTableBlock.PageSpan left : a.spans()) {
            for (PdfTableBlock.PageSpan right : b.spans()) {
                if (left.page() != right.page()) continue;
                float overlap = Math.min(left.bottom(), right.bottom())
                        - Math.max(left.top(), right.top());
                float minHeight = Math.min(left.bottom() - left.top(), right.bottom() - right.top());
                if (overlap > 0 && minHeight > 0 && overlap >= minHeight * 0.5f) return true;
            }
        }
        return false;
    }

    private static boolean similar(String first, String second) {
        String a = normalizeTableText(first);
        String b = normalizeTableText(second);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() >= 50 && longer.contains(shorter)) return true;
        Set<String> left = grams(a);
        Set<String> right = grams(b);
        if (left.isEmpty() || right.isEmpty()) return false;
        int intersection = 0;
        for (String gram : left) if (right.contains(gram)) intersection++;
        return (double) intersection / Math.max(left.size(), right.size()) >= 0.88;
    }

    private static String normalizeTableText(String html) {
        return MarkdownText.toPlain(html).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static Set<String> grams(String value) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= value.length(); i++) grams.add(value.substring(i, i + 3));
        return grams;
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
