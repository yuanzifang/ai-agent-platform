package com.platform.knowledge.service;

import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 识别 PDF 解析结果中无法自动确认的复杂场景，写入人工复核告警。 */
final class PdfManualReviewInspector {

    private static final Pattern TABLE_CAPTION = Pattern.compile("(?i)(?:\\btable\\s+\\d+|表\\s*\\d+)");
    private static final String PREFIX = "人工复核：";

    private PdfManualReviewInspector() { }

    static void inspect(List<DocumentParser.ParsedChunk> chunks, List<String> warnings) {
        if (chunks == null || chunks.isEmpty() || warnings == null) return;
        StringBuilder text = new StringBuilder();
        boolean hasTable = false;
        boolean complexTable = false;
        boolean sparseTable = false;
        boolean chartUnverified = false;
        for (DocumentParser.ParsedChunk chunk : chunks) {
            if (chunk == null || chunk.content() == null) continue;
            String content = chunk.content();
            text.append(content).append('\n');
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.contains("<table") || content.startsWith("|")) hasTable = true;
            if (lower.contains("<table")) {
                if (lower.contains("rowspan=") || lower.contains("colspan=")) complexTable = true;
                var cells = Jsoup.parseBodyFragment(content).select("td,th");
                if (cells.size() >= 20) {
                    long empty = cells.stream().filter(cell -> cell.text().isBlank()).count();
                    if (empty * 2 > cells.size()) sparseTable = true;
                }
            }
            if (lower.contains("\"ischart\"")
                    && (lower.contains("\"validationstatus\":\"unverified\"")
                    || lower.contains("\"validationstatus\":\"conflict\""))) {
                chartUnverified = true;
            }
        }
        if (complexTable) add(warnings, PREFIX + "检测到复杂合并单元格表格，自动解析可能不完整，请人工核对");
        if (sparseTable) add(warnings, PREFIX + "检测到空单元格比例较高的表格，列/合并结构可能不完整，请人工核对");
        if (!hasTable && TABLE_CAPTION.matcher(text).find()) {
            add(warnings, PREFIX + "检测到表格标题但未生成结构化表格，请人工核对");
        }
        if (chartUnverified) add(warnings, PREFIX + "图表数据尚未通过原文数值校验，请人工核对");
    }

    private static void add(List<String> warnings, String warning) {
        if (warnings.stream().anyMatch(existing -> existing.contains(warning))) return;
        warnings.add(warning);
    }
}
