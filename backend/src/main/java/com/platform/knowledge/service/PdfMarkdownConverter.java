package com.platform.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF → 结构化 Markdown。
 *
 * <p>在 PDFBox 文本层之上做轻量版面分析：按行/段落重组、字号与字重识别标题、列表识别、
 * 页眉页脚与页码清理、表格原位插入、页码锚点、扫描页按页 OCR。不引入新的 Python 服务，
 * 复杂版面仍可由后续可选后端接管。</p>
 *
 * <p>输出约定：</p>
 * <ul>
 *   <li>每页以 {@code <!-- page: N -->} 开头，供分块记录页码；</li>
 *   <li>简单表格输出 Markdown 管道表，含 colspan/rowspan 的表格保留 HTML；</li>
 *   <li>表格文字会从正文流中剔除，避免重复，并插回原页码位置。</li>
 * </ul>
 */
@Slf4j
final class PdfMarkdownConverter {

    private static final int OCR_MIN_CHARS = 20;
    private static final float HEADING_MIN_RATIO = 1.12f;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^\\s*(?:第\\s*)?\\d{1,4}\\s*(?:页|/\\s*\\d{1,4})?\\s*$");
    private static final Pattern BULLET = Pattern.compile(
            "^\\s*[•·●○◦▪▫■□◆◇►▶‣⁃\\-–—*]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d{1,3})[.)、．]\\s*(.*)$");
    private static final Pattern PAREN_NUMBERED = Pattern.compile("^\\s*[（(](\\d{1,3})[)）]\\s*(.*)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})[\\s、.．].*");
    private static final Pattern NAMED_HEADING = Pattern.compile(
            "^(摘要|abstract|引言|introduction|结论|conclusion|参考文献|references|附录|appendix"
                    + "|目录|contents|前言|概述|总结|致谢|acknowledg).*",
            Pattern.CASE_INSENSITIVE);

    interface PageOcr {
        OcrResult ocr(PDDocument doc, int pageIndex, ParseOptions options) throws Exception;
    }

    record OcrResult(String text, String tableHtml, double confidence) {
        OcrResult(String text, String tableHtml) {
            this(text, tableHtml, 0);
        }
    }

    private final ParseOptions opts;
    private final PageOcr ocr;
    private final java.util.function.Consumer<String> warnings;
    private final int ocrConcurrency;
    private final Set<Integer> ocrPages = new HashSet<>();

    PdfMarkdownConverter(ParseOptions opts, PageOcr ocr,
                         java.util.function.Consumer<String> warnings, int ocrConcurrency) {
        this.opts = opts;
        this.ocr = ocr;
        this.warnings = warnings == null ? message -> { } : warnings;
        this.ocrConcurrency = Math.max(1, Math.min(4, ocrConcurrency));
    }

    Set<Integer> ocrPages() {
        return ocrPages;
    }

    String convert(PDDocument doc) throws Exception {
        ocrPages.clear();
        int pageCount = doc.getNumberOfPages();
        if (pageCount > 1000) {
            warnings.accept("人工复核：PDF 共 " + pageCount + " 页，表格提取仅覆盖前 1000 页，后续页面请人工核对");
        }
        Map<Integer, List<PdfTableBlock>> tablesByPage = new HashMap<>();
        for (PdfTableBlock block : PdfTableLayout.extract(doc)) {
            for (PdfTableBlock.PageSpan span : block.spans()) {
                tablesByPage.computeIfAbsent(span.page(), key -> new ArrayList<>()).add(block);
            }
            if (block.spans().isEmpty()) {
                tablesByPage.computeIfAbsent(block.pageFrom(), key -> new ArrayList<>()).add(block);
            }
        }

        List<PageState> states = new ArrayList<>();
        List<PageState> ocrCandidates = new ArrayList<>();
        int ocrSkipped = 0;
        int noTextPages = 0;
        int maxOcr = Math.max(1, opts.ocrMaxPages());
        for (int p = 0; p < pageCount; p++) {
            PDPage page = doc.getPage(p);
            float height = page.getMediaBox().getHeight();
            float width = page.getMediaBox().getWidth();
            List<Line> lines = extractLines(doc, p, width);
            PageState state = new PageState(p, height, width, lines);
            states.add(state);
            boolean emptyPage = textLength(lines) < OCR_MIN_CHARS;
            if (!emptyPage) continue;
            if (!opts.ocrEnabled()) {
                noTextPages++;
            } else if (ocr != null && ocrCandidates.size() < maxOcr) {
                ocrCandidates.add(state);
            } else {
                ocrSkipped++;
            }
        }
        int ocrAttempts = ocrCandidates.size();
        if (!ocrCandidates.isEmpty()) {
            List<OcrResult> results = runPageOcr(doc, ocrCandidates);
            for (int i = 0; i < ocrCandidates.size(); i++) {
                PageState state = ocrCandidates.get(i);
                OcrResult result = i < results.size() ? results.get(i) : null;
                if (result == null) continue;
                if (result.text() != null && !result.text().isBlank()) {
                    state.lines = linesFromOcr(result.text(), state.height);
                    ocrPages.add(state.index);
                } else {
                    warnings.accept("第 " + (state.index + 1) + " 页 OCR 未识别到文本");
                }
                if (result.tableHtml() != null && !result.tableHtml().isBlank()) {
                    tablesByPage.computeIfAbsent(state.index, key -> new ArrayList<>())
                            .add(PdfTableBlock.singlePage(state.index, state.height + 1, state.height + 2,
                                    0, state.width, result.tableHtml(), false));
                }
            }
        }
        if (ocrSkipped > 0) {
            warnings.accept("扫描页 OCR 仅覆盖前 " + ocrAttempts + " 页，另有 " + ocrSkipped
                    + " 页无文本未 OCR（上限 " + maxOcr + " 页）；请调高 knowledge.ocr.max-pages 或分批解析");
        }
        if (noTextPages > 0) {
            warnings.accept("有 " + noTextPages + " 页无可提取文本且 OCR 未启用，内容可能缺失；"
                    + "请启用 knowledge.ocr.enabled 或补充文本层");
        }

        List<Page> pages = new ArrayList<>(states.size());
        for (PageState state : states) {
            List<PdfTableBlock> pageTables = new ArrayList<>(tablesByPage.getOrDefault(state.index, List.of()));
            pageTables.sort(Comparator.comparingDouble(PdfTableBlock::top));
            pages.add(new Page(state.index, state.height, state.lines, pageTables));
        }
        removeBoilerplate(pages, pageCount);
        float fallbackBodySize = bodyFontSize(pages);

        StringBuilder out = new StringBuilder();
        for (Page page : pages) {
            float pageBodySize = bodyFontSize(page, fallbackBodySize);
            String markdown = renderPage(page, pageBodySize);
            if (markdown.isBlank()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append("<!-- page: ").append(page.index() + 1).append(" -->\n\n").append(markdown);
        }
        return out.toString().trim();
    }

    /** 页面 OCR：渲染仍由 DocumentParser 串行加锁，tesseract 按配置并发。 */
    private List<OcrResult> runPageOcr(PDDocument doc, List<PageState> candidates) {
        List<OcrResult> results = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) results.add(null);
        if (ocrConcurrency <= 1 || candidates.size() <= 1) {
            for (int i = 0; i < candidates.size(); i++) {
                PageState state = candidates.get(i);
                try {
                    results.set(i, ocr.ocr(doc, state.index, opts));
                } catch (Exception e) {
                    warnings.accept("第 " + (state.index + 1) + " 页 OCR 失败：" + e.getMessage());
                }
            }
            return results;
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(4, Math.max(1, ocrConcurrency)), runnable -> {
                    Thread thread = new Thread(runnable, "pdf-page-ocr");
                    thread.setDaemon(true);
                    return thread;
                });
        List<Future<OcrResult>> futures = new ArrayList<>(candidates.size());
        try {
            for (PageState state : candidates) {
                futures.add(executor.submit(() -> ocr.ocr(doc, state.index, opts)));
            }
            for (int i = 0; i < candidates.size(); i++) {
                PageState state = candidates.get(i);
                try {
                    results.set(i, futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    warnings.accept("页面 OCR 被中断");
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    warnings.accept("第 " + (state.index + 1) + " 页 OCR 失败：" + cause.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return results;
    }

    private List<Line> extractLines(PDDocument doc, int pageIndex, float pageWidth) {
        List<TextPosition> glyphs = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                glyphs.add(text);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        try {
            stripper.getText(doc);
        } catch (Exception e) {
            log.debug("PDF 第 {} 页文本层读取失败：{}", pageIndex + 1, e.getMessage());
            return new ArrayList<>();
        }
        return orderForReading(toLines(glyphs), pageWidth);
    }

    private List<Line> toLines(List<TextPosition> glyphs) {
        glyphs.sort(Comparator.comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));
        List<Line> lines = new ArrayList<>();
        Line current = null;
        for (TextPosition glyph : glyphs) {
            String unicode = glyph.getUnicode();
            if (unicode == null || unicode.isEmpty()) continue;
            float y = glyph.getYDirAdj();
            float fontSize = glyph.getFontSizeInPt() > 0 ? glyph.getFontSizeInPt() : 10f;
            float tolerance = Math.max(2f, fontSize * 0.4f);
            boolean newLine = current == null || Math.abs(current.y - y) > tolerance;
            if (!newLine) {
                float gap = glyph.getXDirAdj() - current.endX;
                if (gap > Math.max(30f, fontSize * 3.0f)) newLine = true;
            }
            if (newLine) {
                current = new Line(y);
                lines.add(current);
            }
            current.add(glyph);
        }
        for (Line line : lines) line.build();
        lines.removeIf(line -> line.text.isBlank());
        return lines;
    }

    /**
     * 两栏/多栏阅读顺序：整栏优先，而不是按 y 逐行左右交错。
     * 检测到左右各至少 3 行、且跨中缝的行较少时，以跨栏整行作为分段边界。
     */
    private List<Line> orderForReading(List<Line> lines, float pageWidth) {
        int left = 0, middle = 0, right = 0;
        for (Line line : lines) {
            if (isFullWidth(line, pageWidth)) continue;
            float center = (line.x + line.endX) / 2f;
            if (center < pageWidth * 0.33f) left++;
            else if (center <= pageWidth * 0.67f) middle++;
            else right++;
        }
        lines.sort(Comparator.comparingDouble(line -> line.y));
        boolean threeColumns = left >= 3 && middle >= 3 && right >= 3;
        if (!threeColumns && (left < 3 || right < 3)) return lines;

        List<Line> ordered = new ArrayList<>();
        List<Line> segment = new ArrayList<>();
        for (Line line : lines) {
            if (isFullWidth(line, pageWidth)) {
                flushSegment(ordered, segment, pageWidth, threeColumns);
                ordered.add(line);
            } else {
                segment.add(line);
            }
        }
        flushSegment(ordered, segment, pageWidth, threeColumns);
        return ordered;
    }

    private void flushSegment(List<Line> ordered, List<Line> segment, float pageWidth,
                              boolean threeColumns) {
        if (segment.isEmpty()) return;
        List<Line> left = new ArrayList<>();
        List<Line> middle = new ArrayList<>();
        List<Line> right = new ArrayList<>();
        List<Line> other = new ArrayList<>();
        for (Line line : segment) {
            float center = (line.x + line.endX) / 2f;
            if (center < pageWidth * 0.33f) left.add(line);
            else if (center <= pageWidth * 0.67f) middle.add(line);
            else right.add(line);
        }
        if (threeColumns && left.size() >= 2 && middle.size() >= 2 && right.size() >= 2) {
            left.sort(Comparator.comparingDouble(line -> line.y));
            middle.sort(Comparator.comparingDouble(line -> line.y));
            right.sort(Comparator.comparingDouble(line -> line.y));
            ordered.addAll(left);
            ordered.addAll(middle);
            ordered.addAll(right);
        } else if (!threeColumns && left.size() >= 2 && right.size() >= 2) {
            left.sort(Comparator.comparingDouble(line -> line.y));
            right.sort(Comparator.comparingDouble(line -> line.y));
            ordered.addAll(left);
            ordered.addAll(right);
            other.sort(Comparator.comparingDouble(line -> line.y));
            ordered.addAll(other);
        } else {
            segment.sort(Comparator.comparingDouble(line -> line.y));
            ordered.addAll(segment);
        }
        segment.clear();
    }

    private boolean isFullWidth(Line line, float pageWidth) {
        return line.x < pageWidth * 0.25f && line.endX > pageWidth * 0.75f;
    }

    /** 页眉页脚：跨页重复的顶部/底部文本、纯页码行。文档少于 3 页时不处理，避免误删标题。 */
    private void removeBoilerplate(List<Page> pages, int pageCount) {
        if (pageCount < 3) {
            for (Page page : pages) page.lines.removeIf(line -> isPageNumber(line.text));
            return;
        }
        Map<String, Set<Integer>> top = new HashMap<>();
        Map<String, Set<Integer>> bottom = new HashMap<>();
        for (Page page : pages) {
            for (Line line : page.lines) {
                String normalized = normalizeBoilerplate(line.text);
                if (normalized.length() < 2 || normalized.length() > 80) continue;
                if (line.y < page.height * 0.08f) {
                    top.computeIfAbsent(normalized, key -> new HashSet<>()).add(page.index);
                } else if (line.y > page.height * 0.92f) {
                    bottom.computeIfAbsent(normalized, key -> new HashSet<>()).add(page.index);
                }
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(pageCount * 0.5));
        Set<String> boilerplate = new HashSet<>();
        top.forEach((key, value) -> {
            if (value.size() >= threshold) boilerplate.add(key);
        });
        bottom.forEach((key, value) -> {
            if (value.size() >= threshold) boilerplate.add(key);
        });
        for (Page page : pages) {
            page.lines.removeIf(line -> isPageNumber(line.text)
                    || boilerplate.contains(normalizeBoilerplate(line.text)));
        }
    }

    private String normalizeBoilerplate(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\d\\s\\p{Punct}，。！？；：、“”‘’（）《》【】、·]+", "");
    }

    /** 表格标题/脚注去重：保留数字，避免把“Table 1”和正文“Table”误判成同一行。 */
    private String normalizeSideText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？；：、“”‘’（）《》【】、·]+", "");
    }

    private boolean isPageNumber(String text) {
        return text != null && PAGE_NUMBER.matcher(text).matches();
    }

    private float bodyFontSize(List<Page> pages) {
        java.util.Map<Integer, Integer> histogram = new java.util.HashMap<>();
        for (Page page : pages) {
            for (Line line : page.lines) {
                int weight = Math.min(40, Math.max(1, line.text.replaceAll("\\s", "").length()));
                int bucket = Math.round(line.fontSize * 2);
                histogram.merge(bucket, weight, Integer::sum);
            }
        }
        int bestBucket = 24;
        int bestWeight = -1;
        for (var entry : histogram.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                bestBucket = entry.getKey();
            }
        }
        return bestBucket / 2f;
    }

    private float bodyFontSize(Page page, float fallback) {
        java.util.Map<Integer, Integer> histogram = new java.util.HashMap<>();
        for (Line line : page.lines) {
            int weight = Math.min(40, Math.max(1, line.text.replaceAll("\\s", "").length()));
            int bucket = Math.round(line.fontSize * 2);
            histogram.merge(bucket, weight, Integer::sum);
        }
        int bestBucket = -1;
        int bestWeight = 0;
        for (var entry : histogram.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                bestBucket = entry.getKey();
            }
        }
        return bestBucket < 0 || bestWeight < 20 ? fallback : bestBucket / 2f;
    }

    private String renderPage(Page page, float bodySize) {
        Set<String> sideTexts = new HashSet<>();
        for (PdfTableBlock table : page.tables) {
            for (String text : MarkdownText.tableSideTexts(table.html())) {
                sideTexts.add(normalizeSideText(text));
            }
        }
        List<Line> lines = new ArrayList<>();
        for (Line line : page.lines) {
            if (insideTable(page.index, line, page.tables)) continue;
            if (sideTexts.contains(normalizeSideText(line.text))) continue;
            if (isPageNumber(line.text)) continue;
            lines.add(line);
        }
        lines.sort(Comparator.comparingDouble(line -> line.y));

        List<PdfTableBlock> tables = new ArrayList<>();
        for (PdfTableBlock table : page.tables) {
            if (table.pageFrom() == page.index) tables.add(table);
        }
        tables.sort(Comparator.comparingDouble(PdfTableBlock::top));

        StringBuilder out = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder list = new StringBuilder();
        StringBuilder headingText = new StringBuilder();
        int headingLevel = 0;
        Line lastHeadingLine = null;
        Line previous = null;
        Line lastListLine = null;
        int tableIndex = 0;
        boolean firstPageTitleFound = false;
        while (tableIndex < tables.size()
                && (lines.isEmpty() || tables.get(tableIndex).top() <= lines.get(0).y)) {
            flushParagraph(out, paragraph);
            flushList(out, list);
            flushHeading(out, headingText, headingLevel);
            appendBlock(out, MarkdownText.htmlTablesToMarkdown(tables.get(tableIndex).html()));
            tableIndex++;
        }
        for (Line line : lines) {
            while (tableIndex < tables.size() && tables.get(tableIndex).top() <= line.y) {
                flushParagraph(out, paragraph);
                flushList(out, list);
                flushHeading(out, headingText, headingLevel);
                appendBlock(out, MarkdownText.htmlTablesToMarkdown(tables.get(tableIndex).html()));
                tableIndex++;
            }
            int level = headingLevel(line, bodySize);
            // 首页长标题（化学/物理论文常见 80 字符以上）单独放宽，但不放宽正文段落。
            if (level == 0 && !firstPageTitleFound && page.index() == 0
                    && line.y < page.height() * 0.22f
                    && line.text.trim().length() >= 20 && line.text.trim().length() <= 220
                    && !line.text.trim().endsWith(".")
                    && !line.text.toLowerCase(Locale.ROOT).startsWith("arxiv:")
                    && line.text.trim().split("\\s+").length >= 4) {
                level = 1;
                firstPageTitleFound = true;
            }
            if (level > 0) {
                if (page.index() == 0 && line.y < page.height() * 0.3f
                        && (line.latexBold || line.fontSize >= bodySize * 1.5f)) {
                    level = 1;
                }
                flushParagraph(out, paragraph);
                flushList(out, list);
                boolean merge = headingText.length() > 0 && level == headingLevel
                        && lastHeadingLine != null
                        && line.y - lastHeadingLine.y <= Math.max(bodySize, line.height) * 1.5f
                        && Math.abs(line.fontSize - lastHeadingLine.fontSize) <= 1.5f;
                if (merge) {
                    headingText.append(' ').append(line.text.trim());
                } else {
                    flushHeading(out, headingText, headingLevel);
                    headingLevel = level;
                    headingText.append(line.text.trim());
                }
                lastHeadingLine = line;
                previous = null;
                continue;
            }
            flushHeading(out, headingText, headingLevel);
            String listItem = listItem(line, page.minX());
            if (listItem != null) {
                flushParagraph(out, paragraph);
                if (list.length() > 0) list.append('\n');
                list.append(listItem);
                lastListLine = line;
                previous = null;
                continue;
            }
            if (list.length() > 0 && isListContinuation(lastListLine, line, bodySize)) {
                list.append(' ').append(line.text.trim());
                lastListLine = line;
                continue;
            }
            if (list.length() > 0) flushList(out, list);
            if (paragraph.length() == 0) {
                paragraph.append(line.text.trim());
            } else if (shouldMerge(previous, line, bodySize)) {
                mergeParagraph(paragraph, previous.text, line.text.trim());
            } else {
                flushParagraph(out, paragraph);
                paragraph.append(line.text.trim());
            }
            previous = line;
        }
        flushParagraph(out, paragraph);
        flushList(out, list);
        flushHeading(out, headingText, headingLevel);
        while (tableIndex < tables.size()) {
            appendBlock(out, MarkdownText.htmlTablesToMarkdown(tables.get(tableIndex).html()));
            tableIndex++;
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    private void flushHeading(StringBuilder out, StringBuilder heading, int level) {
        if (heading.length() == 0) return;
        appendBlock(out, "#".repeat(Math.max(1, level)) + " " + heading);
        heading.setLength(0);
    }

    private boolean isListContinuation(Line previous, Line current, float bodySize) {
        if (previous == null) return false;
        float gap = current.y - previous.y;
        float lineHeight = Math.max(bodySize, Math.max(previous.height, current.height));
        if (gap < 0 || gap > lineHeight * 1.7f) return false;
        String prev = previous.text.trim();
        if (prev.endsWith("。") || prev.endsWith("！") || prev.endsWith("？")
                || prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?")) {
            String next = current.text.trim();
            if (!next.isEmpty() && !Character.isLowerCase(next.charAt(0))) return false;
        }
        return current.x >= previous.x - 2;
    }

    private void flushList(StringBuilder out, StringBuilder list) {
        if (list.length() == 0) return;
        appendBlock(out, list.toString());
        list.setLength(0);
    }

    private boolean insideTable(int pageIndex, Line line, List<PdfTableBlock> tables) {
        float center = line.y + line.height / 2;
        for (PdfTableBlock table : tables) {
            for (PdfTableBlock.PageSpan span : table.spans()) {
                if (span.page() != pageIndex) continue;
                if (center < span.top() - 2 || center > span.bottom() + 2) continue;
                if (line.endX < span.left() - 5 || line.x > span.right() + 5) continue;
                return true;
            }
        }
        return false;
    }

    private void flushParagraph(StringBuilder out, StringBuilder paragraph) {
        String value = paragraph.toString().trim();
        if (!value.isEmpty()) appendBlock(out, value);
        paragraph.setLength(0);
    }

    private void appendBlock(StringBuilder out, String block) {
        if (block == null || block.isBlank()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(block.trim());
    }

    private void mergeParagraph(StringBuilder paragraph, String previous, String current) {
        if (previous.endsWith("-") && !current.isEmpty()
                && Character.isLowerCase(current.charAt(0))) {
            paragraph.setLength(paragraph.length() - 1);
            paragraph.append(current);
            return;
        }
        if (!previous.isEmpty() && !current.isEmpty()
                && (MarkdownText.endsWithCjk(previous) || MarkdownText.startsWithCjk(current))) {
            paragraph.append(current);
            return;
        }
        paragraph.append(' ').append(current);
    }

    private boolean shouldMerge(Line previous, Line current, float bodySize) {
        if (previous == null) return false;
        float gap = current.y - previous.y;
        float lineHeight = Math.max(bodySize, Math.max(previous.height, current.height));
        if (gap < -lineHeight || gap > lineHeight * 1.85f) return false;
        String prev = previous.text.trim();
        if (prev.isEmpty()) return false;
        boolean sentenceEnd = prev.endsWith("。") || prev.endsWith("！") || prev.endsWith("？")
                || prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?");
        if (sentenceEnd && gap > lineHeight * 1.3f) return false;
        if (prev.endsWith("：") || prev.endsWith(":")) return gap <= lineHeight * 1.45f;
        return true;
    }

    private int headingLevel(Line line, float bodySize) {
        String text = line.text.trim();
        if (text.isEmpty() || text.length() > 80) return 0;
        if (BULLET.matcher(text).matches()) return 0;
        if (text.matches("^[\\d\\s.,%:/()\\-+]+$")) return 0;
        long digits = text.chars().filter(Character::isDigit).count();
        if (digits > text.length() * 0.3 && text.length() < 60) return 0;
        float ratio = bodySize <= 0 ? 1f : line.fontSize / bodySize;
        boolean numbered = NUMBERED_HEADING.matcher(text).matches()
                || text.matches("^第[一二三四五六七八九十百零\\d]+[章节篇部分].*");
        boolean named = NAMED_HEADING.matcher(text).matches();
        boolean numberedList = NUMBERED.matcher(text).matches() || PAREN_NUMBERED.matcher(text).matches();
        if (numberedList && !line.bold && ratio < HEADING_MIN_RATIO) return 0;
        boolean looksHeading = ratio >= HEADING_MIN_RATIO
                || (line.bold && ratio >= 1.02f)
                || (line.latexBold && ratio >= 0.98f)
                || (numbered && (line.bold || ratio >= 1.03f))
                || (named && text.length() <= 60 && (line.bold || ratio >= 1.05f));
        if (!looksHeading) return 0;
        if (text.endsWith("。") || text.endsWith("，") || text.endsWith("；")
                || text.endsWith(",") || text.endsWith(";")) return 0;
        int level;
        if (ratio >= 1.6f) level = 1;
        else if (ratio >= 1.32f) level = 2;
        else if (ratio >= 1.12f) level = 3;
        else level = 4;
        if (numbered) {
            Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(text);
            if (matcher.find()) {
                int depth = matcher.group(1).split("\\.").length - 1;
                level = Math.min(level, depth + 1);
            }
        }
        return Math.max(1, Math.min(4, level));
    }

    private String listItem(Line line, float pageMinX) {
        String text = line.text.trim();
        Matcher bullet = BULLET.matcher(text);
        if (bullet.matches()) {
            return indent(line.x - pageMinX) + "- " + bullet.group(1).trim();
        }
        Matcher numbered = NUMBERED.matcher(text);
        if (numbered.matches()) {
            return indent(line.x - pageMinX) + numbered.group(1) + ". " + numbered.group(2).trim();
        }
        Matcher paren = PAREN_NUMBERED.matcher(text);
        if (paren.matches()) {
            return indent(line.x - pageMinX) + paren.group(1) + ". " + paren.group(2).trim();
        }
        return null;
    }

    private String indent(float offset) {
        int level = Math.max(0, Math.min(3, Math.round(offset / 18f)));
        return "  ".repeat(level);
    }

    private long textLength(List<Line> lines) {
        long length = 0;
        for (Line line : lines) length += line.text.replaceAll("\\s", "").length();
        return length;
    }

    private List<Line> linesFromOcr(String text, float pageHeight) {
        List<Line> lines = new ArrayList<>();
        // OCR 没有坐标；从正文区域开始，避免统一 y=0 被页眉页脚去重规则误删。
        float y = Math.max(40f, pageHeight * 0.10f);
        for (String part : text.split("\n")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            Line line = new Line(y);
            line.text = value;
            line.x = 0;
            line.endX = value.length() * 8f;
            line.fontSize = 12f;
            line.height = 14f;
            line.bold = false;
            lines.add(line);
            y += 18f;
        }
        return lines;
    }

    private static final class PageState {
        private final int index;
        private final float height;
        private final float width;
        private List<Line> lines;

        private PageState(int index, float height, float width, List<Line> lines) {
            this.index = index;
            this.height = height;
            this.width = width;
            this.lines = lines;
        }
    }

    private record Page(int index, float height, List<Line> lines,
                        List<PdfTableBlock> tables) {
        private float minX() {
            float min = Float.MAX_VALUE;
            for (Line line : lines) min = Math.min(min, line.x);
            return min == Float.MAX_VALUE ? 0 : min;
        }
    }

    private static final class Line {
        private final float y;
        private final List<TextPosition> glyphs = new ArrayList<>();
        private String text = "";
        private float x;
        private float endX;
        private float fontSize = 10f;
        private float height = 12f;
        private boolean bold;
        private boolean latexBold;

        private Line(float y) {
            this.y = y;
        }

        private void add(TextPosition glyph) {
            glyphs.add(glyph);
            float glyphEnd = glyph.getXDirAdj() + glyph.getWidthDirAdj();
            if (glyphs.size() == 1) x = glyph.getXDirAdj();
            endX = Math.max(endX, glyphEnd);
            fontSize = Math.max(fontSize, glyph.getFontSizeInPt() > 0 ? glyph.getFontSizeInPt() : 10f);
            height = Math.max(height, Math.max(glyph.getHeightDir(), fontSize));
        }

        private void build() {
            if (glyphs.isEmpty()) return;
            glyphs.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
            StringBuilder value = new StringBuilder();
            TextPosition previous = null;
            int glyphCount = 0;
            int boldCount = 0;
            int latexBoldCount = 0;
            for (TextPosition glyph : glyphs) {
                String unicode = glyph.getUnicode();
                if (unicode == null || unicode.isEmpty()) continue;
                unicode = unicode.replace('\u00A0', ' ');
                if (previous != null && needsSpace(previous, glyph, value)) value.append(' ');
                value.append(unicode);
                previous = glyph;
                glyphCount++;
                if (isBold(glyph)) boldCount++;
                if (isLatexBold(glyph)) latexBoldCount++;
            }
            bold = glyphCount > 0 && boldCount >= glyphCount * 0.6;
            latexBold = glyphCount > 0 && latexBoldCount >= glyphCount * 0.6;
            text = value.toString().replaceAll("[ \\t]{2,}", " ").trim();
            if (text.isEmpty()) return;
            fontSize = Math.max(fontSize, 10f);
            height = Math.max(height, fontSize);
        }

        private boolean needsSpace(TextPosition previous, TextPosition current, StringBuilder value) {
            if (value.length() == 0) return false;
            char prev = value.charAt(value.length() - 1);
            char next = current.getUnicode().isEmpty() ? ' ' : current.getUnicode().charAt(0);
            if (Character.isWhitespace(prev)) return false;
            float gap = current.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj());
            float size = Math.max(previous.getFontSizeInPt(), current.getFontSizeInPt());
            if (gap <= Math.max(0.8f, size * 0.18f)) return false;
            if (MarkdownText.isCjk(prev) || MarkdownText.isCjk(next)) {
                return gap > size * 1.2f;
            }
            if ("([{/<".indexOf(prev) >= 0) return false;
            if (",.;:!?)]}%".indexOf(next) >= 0) return false;
            return true;
        }

        private boolean isLatexBold(TextPosition glyph) {
            try {
                String name = glyph.getFont() == null || glyph.getFont().getName() == null
                        ? "" : glyph.getFont().getName().toLowerCase(Locale.ROOT);
                return name.contains("cmbx") || name.contains("cmssbx");
            } catch (Exception e) {
                return false;
            }
        }

        private boolean isBold(TextPosition glyph) {
            try {
                String name = glyph.getFont() == null || glyph.getFont().getName() == null
                        ? "" : glyph.getFont().getName().toLowerCase(Locale.ROOT);
                if (name.contains("bold") || name.contains("black") || name.contains("heavy")
                        || name.contains("demi") || name.contains("cmbx") || name.contains("cmssbx")) {
                    return true;
                }
                var descriptor = glyph.getFont() == null ? null : glyph.getFont().getFontDescriptor();
                return descriptor != null && descriptor.getFontWeight() >= 600;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
