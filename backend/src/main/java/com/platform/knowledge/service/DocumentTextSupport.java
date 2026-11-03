package com.platform.knowledge.service;

/** 文档解析轻量文本工具。 */
final class DocumentTextSupport {

    private DocumentTextSupport() {
    }

    /** OCR 文本质量：字母/数字/中文占非空白字符比例；用于增强图和原图结果取舍。 */
    static double ocrQuality(String text) {
        if (text == null || text.isBlank()) return 0;
        int good = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetterOrDigit(c) || (c >= 0x4E00 && c <= 0x9FFF)) good++;
        }
        return total == 0 ? 0 : (double) good / total;
    }

    static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp") || lower.endsWith(".tif") || lower.endsWith(".tiff")
                || lower.endsWith(".webp");
    }

    static String extension(String lower) {
        int idx = lower.lastIndexOf('.');
        return idx >= 0 ? lower.substring(idx) : ".img";
    }
}
