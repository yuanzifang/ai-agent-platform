package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import com.platform.common.security.NetworkPolicyService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * 受控 URL 抓取器：只允许公网 HTTP(S)，每次重定向重新做 SSRF 校验，
 * 并在读取响应体时强制大小上限，避免把超大响应完整载入内存。
 */
@Component
public class KnowledgeUrlFetcher {

    private final NetworkPolicyService networkPolicy;

    public KnowledgeUrlFetcher(NetworkPolicyService networkPolicy) {
        this.networkPolicy = networkPolicy;
    }

    public record Fetched(byte[] bytes, String contentType, String finalUrl) {
    }

    public Fetched fetch(String url, int importMaxBytes) throws Exception {
        return fetch(url, importMaxBytes, 0);
    }

    private Fetched fetch(String url, int importMaxBytes, int depth) throws Exception {
        if (depth > 3) throw new BusinessException(400, "URL 重定向次数过多");
        networkPolicy.validatePublicHttpUrl(url, "URL");
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(20))
                // 禁止代理绕过 Dns 校验（需要代理时另行做受控出口）
                .proxy(java.net.Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                // 关键：DNS 在真正建连前再校验一次，阻断校验后 DNS 改指向内网的 rebinding。
                .dns(hostname -> {
                    try {
                        return networkPolicy.resolvePublicHost(hostname);
                    } catch (BusinessException e) {
                        throw new UnknownHostException(e.getMessage());
                    }
                })
                .build();
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "AI-Agent-Platform/1.0")
                .header("Accept", "text/html,text/plain,application/pdf,application/json,text/csv;q=0.9,*/*;q=0.1")
                .get().build();
        // 流式读取，避免检查大小前把超大文件完整读入内存。
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status >= 300 && status < 400) {
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new BusinessException(502, "URL 返回重定向但缺少 Location");
                }
                String next = URI.create(url).resolve(location).toString();
                return fetch(next, importMaxBytes, depth + 1);
            }
            if (status < 200 || status >= 300) {
                throw new BusinessException(502, "URL 抓取失败：HTTP " + status);
            }
            ResponseBody responseBody = response.body();
            long contentLength = responseBody == null ? -1 : responseBody.contentLength();
            if (contentLength > importMaxBytes) throw tooLarge(importMaxBytes);
            String contentType = response.header("Content-Type", "text/html")
                    .toLowerCase().split(";")[0].trim();
            if (!(contentType.startsWith("text/") || contentType.equals("application/pdf")
                    || contentType.equals("application/json")
                    || contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))) {
                throw new BusinessException(400, "URL 内容类型不支持：" + contentType);
            }
            if (responseBody == null) throw new BusinessException(502, "URL 响应体为空");
            try (InputStream body = responseBody.byteStream()) {
                return new Fetched(readBounded(body, importMaxBytes), contentType, url);
            }
        }
    }

    /** 最多读取 importMaxBytes + 1 字节：超过上限立即中止，不把剩余响应体载入内存。 */
    private byte[] readBounded(InputStream body, int importMaxBytes) throws Exception {
        int limit = Math.max(1, importMaxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw tooLarge(importMaxBytes);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private BusinessException tooLarge(int importMaxBytes) {
        return new BusinessException(413,
                "URL 内容超过 " + (importMaxBytes / 1024 / 1024) + "MB 限制");
    }

    /** 从 URL 和 Content-Type 推导安全文件名；未知类型补文本扩展名。 */
    public String fileNameFor(String url, String contentType) {
        String name = "url-import";
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isBlank()) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) name = last;
            }
        } catch (Exception ignored) {
            // URL 已在抓取阶段校验，这里仅做文件名推导
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        String lower = name.toLowerCase();
        boolean hasKnownExt = lower.matches(".*\\.(html?|pdf|txt|md|csv|json|docx)$");
        if (hasKnownExt) return name;
        String ext = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "text/csv" -> ".csv";
            case "text/markdown" -> ".md";
            case "text/html" -> ".html";
            default -> ".txt";
        };
        return name + ext;
    }

    public String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
