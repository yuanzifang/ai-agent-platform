package com.platform.search;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 全局搜索的轻量中文分析器。
 *
 * <p>使用 Jieba 的 SEARCH 模式，既保留“客服助手”这类完整词，也会拆出“客服/助手”等
 * 可命中词；英文和数字按原词保留。这里只负责查询分析和展示，不改变各模块的权限过滤。</p>
 */
public final class ChineseTextAnalyzer {

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "与", "及", "或", "是", "在", "我", "你", "他", "她", "它", "们",
            "请", "帮我", "找", "一下", "有关", "关于", "一个", "这个", "那个", "怎么", "如何");

    private ChineseTextAnalyzer() {
    }

    /** 返回去重后的查询词；空查询返回空列表。最多保留 12 个词，防止异常长输入放大查询成本。 */
    public static List<String> tokenize(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return List.of();

        Set<String> words = new LinkedHashSet<>();
        for (SegToken token : SEGMENTER.process(normalized, JiebaSegmenter.SegMode.SEARCH)) {
            String word = normalize(token.word);
            if (!isUseful(word) || STOP_WORDS.contains(word)) continue;
            words.add(word);
            if (words.size() >= 12) break;
        }
        // Jieba 对纯符号/极短输入可能没有返回词，保留完整的规范化查询兜底。
        if (words.isEmpty()) words.add(normalized);
        return List.copyOf(words);
    }

    public static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static boolean isUseful(String word) {
        if (word == null || word.isBlank()) return false;
        return word.codePoints().anyMatch(cp -> Character.isLetterOrDigit(cp) || isHan(cp));
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
