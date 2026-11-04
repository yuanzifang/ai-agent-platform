package com.platform.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 入库前正文清洗。
 *
 * <p>只处理网页/HTML 转 Markdown 时混入的站点壳层，不改写业务正文：
 * 站点标题、导航、无障碍提示、时间/来源/字号/打印/分享等模板不进入 chunk。
 * 页码锚点、标题、表格、代码块和正文保持不变。</p>
 */
final class ContentCleaner {

    private static final Pattern PAGE_ANCHOR = Pattern.compile("^<!--\\s*page:\\s*\\d+\\s*-->$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern TABLE_OR_CODE = Pattern.compile("^\\s*(\\||<table|```|~~~).*$",
            Pattern.CASE_INSENSITIVE);

    /** 单行即导航/模板的行，整行删除。 */
    private static final Pattern NAV_LINE = Pattern.compile(
            "^(?:"
                    + "中国农业农村信息网_.*"
                    + "|无障碍(?:\\s.*)?"
                    + "|农芯慧答.*"
                    + "|中华人民共和国农业农村部.*"
                    + "|首页(?:\\s+机构.*)?"
                    + "|机构\\s+资讯\\s+数据\\s+生产\\s+信息化\\s+专题\\s+视频"
                    + "|首页>[^\\s]*"
                    + "|字体[:：].*"
                    + "|打印.*"
                    + "|分享[:：].*"
                    + "|高级检索.*"
                    + ")$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SITE_PREFIX = Pattern.compile(
            "^中国农业农村信息网_[^\\n]{0,300}?(?:视频|首页>[^\\s]*)\\s*");
    private static final Pattern INLINE_HOME_PATH = Pattern.compile("首页>[^\\s]*\\s*");
    private static final Pattern INLINE_NAV = Pattern.compile(
            "无障碍\\s*|农芯慧答\\s*|中华人民共和国农业农村部\\s*|"
                    + "首页\\s+机构\\s+资讯\\s+数据\\s+生产\\s+信息化\\s+专题\\s+视频\\s*|"
                    + "字体[:：]\\s*\\[?[大中小\\s]*\\]?\\s*|"
                    + "打印\\s*|分享[:：]\\s*|高级检索\\s*");
    private static final Pattern PUBLISH_META = Pattern.compile(
            "^(?:发布时间|时间|来源|作者|编辑)[:：].*$", Pattern.CASE_INSENSITIVE);

    private ContentCleaner() {
    }

    static String clean(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\uFEFF", "")
                .replace("\u200B", "");
        // 第一块常见：“中国农业农村信息网_标题 无障碍 ... 首页>路径 正文”。
        text = SITE_PREFIX.matcher(text).replaceFirst("");
        List<String> kept = new ArrayList<>();
        for (String original : text.split("\n", -1)) {
            String line = original.strip();
            if (line.isEmpty()) {
                kept.add("");
                continue;
            }
            if (PAGE_ANCHOR.matcher(line).matches()
                    || HEADING.matcher(line).matches()
                    || TABLE_OR_CODE.matcher(line).matches()) {
                kept.add(line);
                continue;
            }
            if (NAV_LINE.matcher(line).matches() || PUBLISH_META.matcher(line).matches()) {
                continue;
            }
            String normalized = INLINE_HOME_PATH.matcher(line).replaceAll("");
            normalized = INLINE_NAV.matcher(normalized).replaceAll("").strip();
            if (!normalized.isEmpty()) kept.add(normalized);
        }
        // 折叠连续空行，但保留 Markdown 段落边界。
        StringBuilder out = new StringBuilder(text.length());
        int blank = 0;
        for (String line : kept) {
            if (line.isEmpty()) {
                blank++;
                if (blank <= 2) out.append('\n');
                continue;
            }
            blank = 0;
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }
}
