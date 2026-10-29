package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 飞书文档/知识库/云盘文件夹导入。
 *
 * 支持 docx、wiki 节点树、drive folder；文档文本写入知识库对象存储，
 * 复用现有解析/分块/embedding 流水线。需要飞书应用具备对应只读权限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuDocumentService {

    private static final int MAX_FILES = 100;
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_RETRIES = 3;
    private static final long MIN_API_INTERVAL_MS = 220;

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private long lastApiRequestAt;

    public record Document(String name, byte[] content, String contentType, String sourceUrl) {
    }

    private record Target(String type, String token) {
    }

    public List<Document> collect(String rawUrl, String appId, String appSecret) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new BusinessException(400, "飞书 App ID/Secret 不能为空");
        }
        if (rawUrl == null || rawUrl.isBlank()) throw new BusinessException(400, "飞书文档 URL 不能为空");
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new BusinessException(400, "飞书文档 URL 格式错误");
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")
                || uri.getHost() == null || !uri.getHost().endsWith(".feishu.cn")) {
            throw new BusinessException(400, "仅支持 https://*.feishu.cn 文档/知识库/云盘链接");
        }
        Target target = parseTarget(uri.getPath());
        String token = token(appId, appSecret);
        List<Document> documents = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            switch (target.type()) {
                case "docx" -> add(documents, fetchDocx(target.token(), token, rawUrl,
                        defaultName("飞书文档", target.token())));
                case "doc" -> add(documents, fetchDoc(target.token(), token, rawUrl,
                        defaultName("飞书文档", target.token())));
                case "wiki" -> collectWiki(target.token(), token, documents, seen);
                case "wiki_space" -> collectWikiChildren(target.token(), null, token, documents, seen);
                case "folder" -> collectFolder(target.token(), token, documents, seen);
                default -> throw new BusinessException(400, "无法识别飞书链接类型");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "飞书文档读取失败：" + root(e));
        }
        if (documents.isEmpty()) throw new BusinessException(404, "飞书链接下没有可导入的文档");
        return documents;
    }

    /** 只接受 /docx、/docs、/wiki、/drive/folder 这几种链接，token 取路径最后一段。 */
    private Target parseTarget(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        String[][] prefixes = {
                {"/docx/", "docx"}, {"/docs/", "doc"}, {"/wiki/space/", "wiki_space"},
                {"/wiki/", "wiki"}, {"/drive/folder/", "folder"}
        };
        for (String[] prefix : prefixes) {
            if (path.startsWith(prefix[0])) {
                String token = path.substring(prefix[0].length());
                int slash = token.indexOf('/');
                if (slash >= 0) token = token.substring(0, slash);
                if (token.isBlank()) break;
                return new Target(prefix[1], token);
            }
        }
        throw new BusinessException(400, "无法识别飞书链接类型，请使用 /docx/、/wiki/ 或 /drive/folder/ 链接");
    }

    private void collectWiki(String nodeToken, String accessToken, List<Document> out, Set<String> seen)
            throws Exception {
        if (!seen.add("wiki:" + nodeToken)) return;
        JsonNode node = requestJson("/open-apis/wiki/v2/spaces/get_node?token=" + encode(nodeToken),
                accessToken, "GET", null).path("data");
        if (node.path("node").isObject()) node = node.path("node");
        String objType = node.path("obj_type").asText();
        String objToken = node.path("obj_token").asText();
        String title = node.path("title").asText("飞书文档");
        if ("docx".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            add(out, fetchDocx(objToken, accessToken, "https://www.feishu.cn/docx/" + objToken, title));
        } else if ("doc".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            add(out, fetchDoc(objToken, accessToken, "https://www.feishu.cn/docs/" + objToken, title));
        }
        String spaceId = node.path("space_id").asText();
        String childToken = node.path("node_token").asText();
        if (!spaceId.isBlank() && !childToken.isBlank()) {
            collectWikiChildren(spaceId, childToken, accessToken, out, seen);
        }
    }

    private void collectWikiChildren(String spaceId, String parentToken, String accessToken,
                                     List<Document> out, Set<String> seen) throws Exception {
        String pageToken = null;
        do {
            String path = "/open-apis/wiki/v2/spaces/" + encode(spaceId) + "/nodes?page_size=" + PAGE_SIZE
                    + (parentToken == null ? "" : "&parent_node_token=" + encode(parentToken))
                    + (pageToken == null ? "" : "&page_token=" + encode(pageToken));
            JsonNode data = requestJson(path, accessToken, "GET", null).path("data");
            for (JsonNode item : data.path("items")) {
                collectWikiNode(item, accessToken, out, seen);
            }
            pageToken = data.path("page_token").asText(null);
        } while (pageToken != null && !pageToken.isBlank() && !pageToken.equals("null"));
    }

    private void collectWikiNode(JsonNode node, String accessToken, List<Document> out, Set<String> seen)
            throws Exception {
        String nodeToken = node.path("node_token").asText();
        if (!seen.add("wiki:" + nodeToken)) return;
        String objType = node.path("obj_type").asText();
        String objToken = node.path("obj_token").asText();
        String title = node.path("title").asText("飞书文档");
        if ("docx".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            add(out, fetchDocx(objToken, accessToken, "https://www.feishu.cn/docx/" + objToken, title));
        } else if ("doc".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            add(out, fetchDoc(objToken, accessToken, "https://www.feishu.cn/docs/" + objToken, title));
        }
        String spaceId = node.path("space_id").asText();
        if (node.path("has_child").asBoolean(false) && !spaceId.isBlank() && !nodeToken.isBlank()) {
            collectWikiChildren(spaceId, nodeToken, accessToken, out, seen);
        }
    }

    private void collectFolder(String folderToken, String accessToken, List<Document> out, Set<String> seen)
            throws Exception {
        if (!seen.add("folder:" + folderToken)) return;
        String pageToken = null;
        do {
            String path = "/open-apis/drive/v1/files?folder_token=" + encode(folderToken)
                    + "&page_size=" + PAGE_SIZE + (pageToken == null ? "" : "&page_token=" + encode(pageToken));
            JsonNode data = requestJson(path, accessToken, "GET", null).path("data");
            for (JsonNode file : data.path("files")) {
                String type = file.path("type").asText();
                String token = file.path("token").asText();
                String name = file.path("name").asText("飞书文件");
                if ("folder".equalsIgnoreCase(type)) {
                    collectFolder(token, accessToken, out, seen);
                } else if ("docx".equalsIgnoreCase(type)) {
                    add(out, fetchDocx(token, accessToken, "https://www.feishu.cn/docx/" + token, name));
                } else if ("doc".equalsIgnoreCase(type)) {
                    add(out, fetchDoc(token, accessToken, "https://www.feishu.cn/docs/" + token, name));
                } else if ("file".equalsIgnoreCase(type)) {
                    add(out, fetchMedia(token, name, accessToken));
                }
            }
            pageToken = data.path("page_token").asText(null);
        } while (pageToken != null && !pageToken.isBlank() && !pageToken.equals("null"));
    }

    private Document fetchDocx(String token, String accessToken, String sourceUrl) throws Exception {
        return fetchDocx(token, accessToken, sourceUrl, null);
    }

    private Document fetchDocx(String token, String accessToken, String sourceUrl, String fallbackName)
            throws Exception {
        JsonNode data = requestJson("/open-apis/docx/v1/documents/" + encode(token) + "/raw_content",
                accessToken, "GET", null).path("data");
        String content = data.path("content").asText("");
        if (content.isBlank()) throw new BusinessException(502, "飞书 docx 内容为空：" + token);
        String title = title(data, fallbackName);
        return textDocument(title, content, sourceUrl);
    }

    private Document fetchDoc(String token, String accessToken, String sourceUrl) throws Exception {
        return fetchDoc(token, accessToken, sourceUrl, null);
    }

    private Document fetchDoc(String token, String accessToken, String sourceUrl, String fallbackName)
            throws Exception {
        JsonNode data = requestJson("/open-apis/doc/v2/" + encode(token) + "/raw_content",
                accessToken, "GET", null).path("data");
        String content = data.path("content").asText("");
        if (content.isBlank()) throw new BusinessException(502, "飞书旧版文档内容为空：" + token);
        String title = title(data, fallbackName);
        return textDocument(title, content, sourceUrl);
    }

    private Document fetchMedia(String token, String name, String accessToken) throws Exception {
        String path = "/open-apis/drive/v1/medias/" + encode(token) + "/download";
        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://open.feishu.cn" + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < MAX_RETRIES) {
                Thread.sleep(300L * (attempt + 1));
                continue;
            }
            if (response.statusCode() >= 300) {
                throw new BusinessException(502, "飞书文件下载失败 HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            return new Document(name, response.body(), contentType, "https://www.feishu.cn/drive/file/" + token);
        }
    }

    private Document textDocument(String title, String content, String sourceUrl) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String safe = (title == null || title.isBlank() ? "飞书文档" : title)
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("(?i)\\.(md|markdown|txt)$", "");
        return new Document(safe + ".txt", bytes, "text/plain", sourceUrl);
    }

    private String title(JsonNode data, String fallbackName) {
        String value = data.path("title").asText("");
        if (!value.isBlank()) return value;
        return fallbackName == null || fallbackName.isBlank() ? "飞书文档" : fallbackName;
    }

    private String defaultName(String prefix, String token) {
        if (token == null || token.isBlank()) return prefix;
        return prefix + "-" + token.substring(0, Math.min(8, token.length()));
    }

    private void add(List<Document> out, Document doc) {
        if (out.size() >= MAX_FILES) throw new BusinessException(413, "单次最多导入 " + MAX_FILES + " 个飞书文件");
        long total = out.stream().mapToLong(d -> d.content().length).sum() + doc.content().length;
        if (total > MAX_BYTES) throw new BusinessException(413, "飞书导入总大小超过 50MB");
        out.add(doc);
    }

    private String token(String appId, String appSecret) {
        try {
            JsonNode response = postJson("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                    Map.of("app_id", appId, "app_secret", appSecret));
            int code = response.path("code").asInt(-1);
            if (code != 0) {
                String message = response.path("msg").asText();
                if (code == 21001) {
                    message += "（请检查飞书应用凭证、应用发布状态、IP 白名单或开放平台访问策略）";
                }
                throw new BusinessException(400, "飞书凭证失败（" + code + "）：" + message);
            }
            String token = response.path("tenant_access_token").asText();
            if (token.isBlank()) throw new BusinessException(502, "飞书未返回 tenant_access_token");
            return token;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "飞书凭证请求失败：" + root(e));
        }
    }

    private JsonNode requestJson(String path, String token, String method, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://open.feishu.cn" + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        HttpRequest request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            boolean rateLimited = response.statusCode() == 429
                    || (response.statusCode() >= 400 && responseBody.contains("99991400"));
            if ((rateLimited || response.statusCode() >= 500) && attempt < MAX_RETRIES) {
                Thread.sleep(300L * (attempt + 1));
                continue;
            }
            if (response.statusCode() == 403) {
                throw new BusinessException(403, "飞书 API 无权限：请确认应用已开通对应只读权限并被添加为文档/知识库成员");
            }
            if (response.statusCode() == 404) {
                throw new BusinessException(404, "飞书文档或节点不存在，或应用无权访问");
            }
            if (response.statusCode() >= 300) {
                throw new BusinessException(502, "飞书 API HTTP " + response.statusCode()
                        + "：" + abbreviate(responseBody));
            }
            JsonNode node = objectMapper.readTree(responseBody);
            int code = node.path("code").asInt(-1);
            if (code == 99991400 && attempt < MAX_RETRIES) {
                Thread.sleep(300L * (attempt + 1));
                continue;
            }
            if (code == 131006 || code == 1770032 || code == 99991672) {
                throw new BusinessException(403, "飞书 API 权限不足（" + code + "）：" + node.path("msg").asText());
            }
            if (code != 0) throw new BusinessException(502, "飞书 API 错误：" + node.path("msg").asText());
            return node;
        }
    }

    /** 飞书 docx raw_content 等接口限制 5 QPS；顺序调用时做本地节流。 */
    private synchronized void throttle() throws InterruptedException {
        long wait = MIN_API_INTERVAL_MS - (System.currentTimeMillis() - lastApiRequestAt);
        if (wait > 0) Thread.sleep(wait);
        lastApiRequestAt = System.currentTimeMillis();
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new BusinessException(502, "飞书凭证 HTTP " + response.statusCode());
        return objectMapper.readTree(response.body());
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String abbreviate(String value) {
        return value == null ? "" : value.substring(0, Math.min(300, value.length()));
    }

    private String root(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
