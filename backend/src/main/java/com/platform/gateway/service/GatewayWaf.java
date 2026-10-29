package com.platform.gateway.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 网关高置信度 WAF：方法、长度、控制字符、路径穿越、SQLi/XSS 特征。 */
public final class GatewayWaf {

    private static final Set<String> METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Pattern SQLI = Pattern.compile(
            "(?is)(\\bunion\\b.{0,120}\\bselect\\b|\\bselect\\b.{0,120}\\bfrom\\b"
                    + "|\\b(or|and)\\b\\s+['\"]?\\d+['\"]?\\s*=\\s*['\"]?\\d+"
                    + "|\\b(sleep|benchmark|waitfor)\\s*\\(|\\bwaitfor\\s+delay\\b"
                    + "|['\"]\\s*(--|#)\\s)");
    private static final Pattern XSS = Pattern.compile(
            "(?is)(<script\\b|javascript\\s*:|on(error|load|click|mouseover)\\s*=|"
                    + "data:text/html|%3cscript)");

    private GatewayWaf() {
    }

    /**
     * 返回拒绝原因；null 表示放行。uri/query 可为原始编码值，内部最多解码两次以覆盖双重编码。
     */
    public static String check(String method, String uri, String query) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) return "method " + m;
        if (uri == null || uri.length() > 2048) return "uri length";
        String q = query == null ? "" : query;
        if (q.length() > 8192) return "query length";
        String path = decode(uri);
        String queryDecoded = decode(q);
        if (path == null || queryDecoded == null) return "invalid encoding";
        String all = path + "?" + queryDecoded;
        if (hasControl(path) || hasControl(queryDecoded)) return "control character";
        String normalizedPath = path.replace('\\', '/');
        for (String segment : normalizedPath.split("/")) {
            if ("..".equals(segment)) return "path traversal";
        }
        String lower = all.toLowerCase(Locale.ROOT);
        if (lower.contains("\u0000") || lower.contains("%00")
                || lower.contains("%0d") || lower.contains("%0a")
                || lower.contains("%2e%2e") || lower.contains("%252e")
                || lower.contains("..;/") || lower.contains("%5c..")
                || lower.contains("../") || lower.contains("..\\")) {
            return "path traversal";
        }
        if (SQLI.matcher(queryDecoded).find() || SQLI.matcher(normalizedPath).find()) return "sqli";
        if (XSS.matcher(queryDecoded).find() || XSS.matcher(normalizedPath).find()) return "xss";
        return null;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private static String decode(String value) {
        if (value == null) return "";
        try {
            String once = URLDecoder.decode(value, StandardCharsets.UTF_8);
            return URLDecoder.decode(once, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
