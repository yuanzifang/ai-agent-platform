package com.platform.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 把搜索命中的词包成安全的 &lt;mark&gt; 片段；所有原文本先做 HTML 转义。 */
public final class SearchHighlighter {

    private SearchHighlighter() {
    }

    public static String highlight(String text, List<String> terms) {
        String source = text == null ? "" : text;
        if (source.isEmpty() || terms == null || terms.isEmpty()) return escape(source);

        List<int[]> ranges = new ArrayList<>();
        for (String raw : terms) {
            String term = ChineseTextAnalyzer.normalize(raw);
            if (term.isEmpty() || term.length() > source.length()) continue;
            int from = 0;
            while (from <= source.length() - term.length()) {
                int found = indexOfIgnoreCase(source, term, from);
                if (found < 0) break;
                ranges.add(new int[]{found, found + term.length()});
                from = found + Math.max(1, term.length());
            }
        }
        if (ranges.isEmpty()) return escape(source);

        ranges.sort(Comparator.comparingInt((int[] range) -> range[0]).thenComparingInt(range -> range[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (merged.isEmpty() || range[0] > merged.get(merged.size() - 1)[1]) {
                merged.add(new int[]{range[0], range[1]});
            } else {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            }
        }

        StringBuilder html = new StringBuilder(source.length() + merged.size() * 9);
        int cursor = 0;
        for (int[] range : merged) {
            html.append(escape(source.substring(cursor, range[0])));
            html.append("<mark>").append(escape(source.substring(range[0], range[1]))).append("</mark>");
            cursor = range[1];
        }
        html.append(escape(source.substring(cursor)));
        return html.toString();
    }

    private static int indexOfIgnoreCase(String source, String term, int from) {
        for (int i = from; i <= source.length() - term.length(); i++) {
            if (source.regionMatches(true, i, term, 0, term.length())) return i;
        }
        return -1;
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
