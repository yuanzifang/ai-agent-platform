package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 文件夹/知识库树和正文超链接文档最多导入三层：根层为 1，子层为 2，孙层为 3。 */
    private static final int MAX_NESTING_DEPTH = 3;
    private static final Pattern FEISHU_DOC_URL = Pattern.compile(
            "https?://[^\\s\"'<>]*\\.feishu\\.cn/(?:docx|docs|wiki)/[^\\s\"'<>，。；、）】]+",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    @Value("${knowledge.feishu.api-base:https://open.feishu.cn}")
    private String apiBase = "https://open.feishu.cn";
    private long lastApiRequestAt;

    public record Document(String name, byte[] content, String contentType, String sourceUrl) {
    }

    /** documents 为实际可导入内容；skipped 记录不支持的资源或超过三层嵌套的节点。 */
    public record Collection(List<Document> documents, List<String> skipped) {
    }

    private record Target(String type, String token) {
    }

    public List<Document> collect(String rawUrl, String appId, String appSecret) {
        return collectDetailed(rawUrl, appId, appSecret).documents();
    }

    public Collection collectDetailed(String rawUrl, String appId, String appSecret) {
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
        List<String> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            switch (target.type()) {
                case "docx" -> addDocx(target.token(), token, rawUrl,
                        defaultName("飞书文档", target.token()), documents, skipped, seen, 1);
                case "doc" -> addDoc(target.token(), token, rawUrl,
                        defaultName("飞书文档", target.token()), documents, skipped, seen, 1);
                case "wiki" -> collectWiki(target.token(), token, documents, skipped, seen, 1);
                case "wiki_space" -> collectWikiChildren(target.token(), null, token, documents,
                        skipped, seen, 1);
                case "folder" -> collectFolder(target.token(), token, documents, skipped, seen, 1);
                case "sheet" -> addSheet(target.token(), token, rawUrl,
                        defaultName("飞书表格", target.token()), documents, skipped, seen);
                default -> throw new BusinessException(400, "无法识别飞书链接类型");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "飞书文档读取失败：" + root(e));
        }
        if (documents.isEmpty()) {
            String detail = skipped.isEmpty() ? "" : "；已跳过：" + String.join("、",
                    skipped.subList(0, Math.min(3, skipped.size())));
            throw new BusinessException(404, "飞书链接下没有可导入的文档" + detail);
        }
        return new Collection(List.copyOf(documents), List.copyOf(skipped));
    }

    /** 只接受 /docx、/docs、/wiki、/drive/folder 这几种链接，token 取路径最后一段。 */
    private Target parseTarget(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        String[][] prefixes = {
                {"/docx/", "docx"}, {"/docs/", "doc"}, {"/wiki/space/", "wiki_space"},
                {"/wiki/", "wiki"}, {"/drive/folder/", "folder"}, {"/sheets/", "sheet"}
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

    private void collectWiki(String nodeToken, String accessToken, List<Document> out,
                             List<String> skipped, Set<String> seen, int depth) throws Exception {
        if (!seen.add("wiki:" + nodeToken)) return;
        JsonNode node = requestJson("/open-apis/wiki/v2/spaces/get_node?token=" + encode(nodeToken),
                accessToken, "GET", null).path("data");
        if (node.path("node").isObject()) node = node.path("node");
        String objType = node.path("obj_type").asText();
        String objToken = node.path("obj_token").asText();
        String title = node.path("title").asText("飞书文档");
        if ("docx".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            addDocx(objToken, accessToken, "https://www.feishu.cn/docx/" + objToken, title,
                    out, skipped, seen, 1);
        } else if ("doc".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            addDoc(objToken, accessToken, "https://www.feishu.cn/docs/" + objToken, title,
                    out, skipped, seen, 1);
        } else if (!objType.isBlank()) {
            skip(skipped, "暂不支持的知识库节点类型 " + objType + "：" + title);
        }
        String spaceId = node.path("space_id").asText();
        String childToken = node.path("node_token").asText();
        if (!spaceId.isBlank() && !childToken.isBlank()) {
            if (depth < MAX_NESTING_DEPTH) {
                collectWikiChildren(spaceId, childToken, accessToken, out, skipped, seen, depth + 1);
            } else {
                skip(skipped, "超过三层嵌套，跳过子节点：" + title);
            }
        }
    }

    private void collectWikiChildren(String spaceId, String parentToken, String accessToken,
                                     List<Document> out, List<String> skipped, Set<String> seen, int depth)
            throws Exception {
        String pageToken = null;
        do {
            String path = "/open-apis/wiki/v2/spaces/" + encode(spaceId) + "/nodes?page_size=" + PAGE_SIZE
                    + (parentToken == null ? "" : "&parent_node_token=" + encode(parentToken))
                    + (pageToken == null ? "" : "&page_token=" + encode(pageToken));
            JsonNode data = requestJson(path, accessToken, "GET", null).path("data");
            for (JsonNode item : data.path("items")) {
                collectWikiNode(item, accessToken, out, skipped, seen, depth);
            }
            pageToken = data.path("page_token").asText(null);
        } while (pageToken != null && !pageToken.isBlank() && !pageToken.equals("null"));
    }

    private void collectWikiNode(JsonNode node, String accessToken, List<Document> out,
                                 List<String> skipped, Set<String> seen, int depth) throws Exception {
        String nodeToken = node.path("node_token").asText();
        if (!seen.add("wiki:" + nodeToken)) return;
        String objType = node.path("obj_type").asText();
        String objToken = node.path("obj_token").asText();
        String title = node.path("title").asText("飞书文档");
        if ("docx".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            addDocx(objToken, accessToken, "https://www.feishu.cn/docx/" + objToken, title,
                    out, skipped, seen, 1);
        } else if ("doc".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            addDoc(objToken, accessToken, "https://www.feishu.cn/docs/" + objToken, title,
                    out, skipped, seen, 1);
        } else if (!objType.isBlank()) {
            skip(skipped, "暂不支持的知识库节点类型 " + objType + "：" + title);
        }
        String spaceId = node.path("space_id").asText();
        if (node.path("has_child").asBoolean(false) && !spaceId.isBlank() && !nodeToken.isBlank()) {
            if (depth < MAX_NESTING_DEPTH) {
                collectWikiChildren(spaceId, nodeToken, accessToken, out, skipped, seen, depth + 1);
            } else {
                skip(skipped, "超过三层嵌套，跳过子节点：" + title);
            }
        }
    }

    private void collectFolder(String folderToken, String accessToken, List<Document> out,
                               List<String> skipped, Set<String> seen, int depth) throws Exception {
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
                    if (depth < MAX_NESTING_DEPTH) {
                        collectFolder(token, accessToken, out, skipped, seen, depth + 1);
                    } else {
                        skip(skipped, "超过三层嵌套，跳过子文件夹：" + name);
                    }
                } else if ("docx".equalsIgnoreCase(type)) {
                    addDocx(token, accessToken, "https://www.feishu.cn/docx/" + token, name,
                            out, skipped, seen, 1);
                } else if ("doc".equalsIgnoreCase(type)) {
                    addDoc(token, accessToken, "https://www.feishu.cn/docs/" + token, name,
                            out, skipped, seen, 1);
                } else if ("file".equalsIgnoreCase(type)) {
                    add(out, fetchMedia(token, name, accessToken));
                } else if ("bitable".equalsIgnoreCase(type)) {
                    if (seen.add("bitable:" + token)) {
                        try {
                            add(out, fetchBitable(token, name, accessToken));
                        } catch (Exception e) {
                            log.warn("飞书多维表格读取失败 token={} err={}", token, root(e));
                            skip(skipped, "多维表格读取失败：" + name + "（" + root(e) + "）");
                        }
                    }
                } else if ("sheet".equalsIgnoreCase(type)) {
                    try {
                        addSheet(token, accessToken, "https://www.feishu.cn/sheets/" + token, name,
                                out, skipped, seen);
                    } catch (Exception e) {
                        log.warn("飞书电子表格读取失败 token={} err={}", token, root(e));
                        skip(skipped, "电子表格读取失败：" + name + "（" + root(e) + "）");
                    }
                } else {
                    skip(skipped, "暂不支持的文件类型 " + type + "：" + name);
                }
            }
            pageToken = data.path("page_token").asText(null);
        } while (pageToken != null && !pageToken.isBlank() && !pageToken.equals("null"));
    }

    private void skip(List<String> skipped, String message) {
        if (skipped.size() < MAX_FILES) skipped.add(message);
    }

    /** 导入 docx，并继续解析正文中的飞书文档超链接；超链接文档各自独立入库。 */
    private void addDocx(String documentToken, String accessToken, String sourceUrl, String fallbackName,
                         List<Document> out, List<String> skipped, Set<String> seen, int depth)
            throws Exception {
        if (!seen.add("docx:" + documentToken)) return;
        List<JsonNode> blocks = null;
        try {
            blocks = fetchDocxBlocks(documentToken, accessToken);
        } catch (Exception e) {
            log.warn("飞书文档 blocks 读取失败，回退 raw_content doc={} err={}", documentToken, root(e));
        }
        Document document = null;
        if (blocks != null) {
            String markdown = renderDocxMarkdown(blocks);
            if (!markdown.isBlank()) {
                document = markdownDocument(fallbackName, markdown, sourceUrl);
            }
        }
        if (document == null) {
            document = fetchDocx(documentToken, accessToken, sourceUrl, fallbackName);
        }
        add(out, document);
        if (depth >= MAX_NESTING_DEPTH) return;
        Set<String> links = new LinkedHashSet<>();
        if (blocks != null) {
            collectLinks(blocks, links);
        } else {
            try {
                links = fetchDocxLinks(documentToken, accessToken);
            } catch (Exception e) {
                log.warn("飞书文档链接解析失败 doc={} err={}", documentToken, root(e));
                skip(skipped, "文档链接解析失败：" + fallbackName);
                return;
            }
        }
        for (String link : links) {
            try {
                collectLinkedUrl(link, accessToken, out, skipped, seen, depth + 1);
            } catch (BusinessException e) {
                skip(skipped, "超链接文档导入失败 " + link + "：" + e.getMessage());
            } catch (Exception e) {
                log.warn("飞书超链接文档读取失败 url={} err={}", link, root(e));
                skip(skipped, "超链接文档导入失败：" + link);
            }
        }
    }

    /** 旧版文档没有 blocks 接口，从纯文本中提取飞书文档链接并继续导入。 */
    private void addDoc(String documentToken, String accessToken, String sourceUrl, String fallbackName,
                        List<Document> out, List<String> skipped, Set<String> seen, int depth)
            throws Exception {
        if (!seen.add("doc:" + documentToken)) return;
        Document document = fetchDoc(documentToken, accessToken, sourceUrl, fallbackName);
        add(out, document);
        if (depth >= MAX_NESTING_DEPTH) return;
        Set<String> links = new LinkedHashSet<>();
        collectLinks(new String(document.content(), StandardCharsets.UTF_8), links);
        for (String link : links) {
            try {
                collectLinkedUrl(link, accessToken, out, skipped, seen, depth + 1);
            } catch (BusinessException e) {
                skip(skipped, "超链接文档导入失败 " + link + "：" + e.getMessage());
            } catch (Exception e) {
                log.warn("飞书超链接文档读取失败 url={} err={}", link, root(e));
                skip(skipped, "超链接文档导入失败：" + link);
            }
        }
    }

    private List<JsonNode> fetchDocxBlocks(String documentToken, String accessToken) throws Exception {
        List<JsonNode> blocks = new ArrayList<>();
        String pageToken = null;
        do {
            String path = "/open-apis/docx/v1/documents/" + encode(documentToken)
                    + "/blocks?page_size=500&document_revision_id=-1"
                    + (pageToken == null ? "" : "&page_token=" + encode(pageToken));
            JsonNode data = requestJson(path, accessToken, "GET", null).path("data");
            for (JsonNode block : data.path("items")) blocks.add(block);
            pageToken = data.path("page_token").asText(null);
            if (!data.path("has_more").asBoolean(false)) pageToken = null;
        } while (pageToken != null && !pageToken.isBlank() && !pageToken.equals("null"));
        return blocks;
    }

    private Set<String> fetchDocxLinks(String documentToken, String accessToken) throws Exception {
        Set<String> links = new LinkedHashSet<>();
        collectLinks(fetchDocxBlocks(documentToken, accessToken), links);
        return links;
    }

    private void collectLinks(List<JsonNode> blocks, Set<String> links) {
        for (JsonNode block : blocks) collectLinks(block, links);
    }

    /** 递归扫描 blocks JSON，提取 link.url、文本中的飞书文档 URL；外部链接会被正则过滤。 */
    private void collectLinks(JsonNode node, Set<String> links) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            collectLinks(node.asText(), links);
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) collectLinks(child, links);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectLinks(entry.getValue(), links));
        }
    }

    private void collectLinks(String text, Set<String> links) {
        if (text == null || text.isBlank()) return;
        Matcher matcher = FEISHU_DOC_URL.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            int end = url.length();
            while (end > 0 && ".,;:!?)]}，。；：！？）】".indexOf(url.charAt(end - 1)) >= 0) end--;
            if (end > 0) links.add(url.substring(0, end));
        }
    }

    private void collectLinkedUrl(String rawUrl, String accessToken, List<Document> out,
                                  List<String> skipped, Set<String> seen, int depth) throws Exception {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (Exception e) {
            return;
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || !uri.getHost().endsWith(".feishu.cn")) return;
        Target target;
        try {
            target = parseTarget(uri.getPath());
        } catch (BusinessException e) {
            skip(skipped, "不支持的文档链接：" + rawUrl);
            return;
        }
        switch (target.type()) {
            case "docx" -> addDocx(target.token(), accessToken, rawUrl,
                    defaultName("飞书链接文档", target.token()), out, skipped, seen, depth);
            case "doc" -> addDoc(target.token(), accessToken, rawUrl,
                    defaultName("飞书链接文档", target.token()), out, skipped, seen, depth);
            case "sheet" -> addSheet(target.token(), accessToken, rawUrl,
                    defaultName("飞书链接表格", target.token()), out, skipped, seen);
            case "wiki" -> collectWikiNodeOnly(target.token(), accessToken, rawUrl, out, skipped, seen, depth);
            default -> skip(skipped, "不支持的文档链接类型：" + rawUrl);
        }
    }

    /** 超链接指向 wiki 节点时只导入该节点本身，不再展开整棵 wiki 树。 */
    private void collectWikiNodeOnly(String nodeToken, String accessToken, String sourceUrl,
                                     List<Document> out, List<String> skipped, Set<String> seen, int depth)
            throws Exception {
        if (!seen.add("wiki:" + nodeToken)) return;
        JsonNode node = requestJson("/open-apis/wiki/v2/spaces/get_node?token=" + encode(nodeToken),
                accessToken, "GET", null).path("data");
        if (node.path("node").isObject()) node = node.path("node");
        String objType = node.path("obj_type").asText();
        String objToken = node.path("obj_token").asText();
        String title = node.path("title").asText("飞书文档");
        if ("docx".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            addDocx(objToken, accessToken, sourceUrl, title, out, skipped, seen, depth);
        } else if ("doc".equalsIgnoreCase(objType) && !objToken.isBlank()) {
            addDoc(objToken, accessToken, sourceUrl, title, out, skipped, seen, depth);
        } else {
            skip(skipped, "暂不支持的超链接文档类型 " + objType + "：" + title);
        }
    }

    /** blocks → Markdown；保留标题、列表、引用、表格和超链接，失败时调用方回退 raw_content。 */
    private String renderDocxMarkdown(List<JsonNode> blocks) {
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode block : blocks) {
            String id = block.path("block_id").asText();
            if (!id.isBlank()) byId.put(id, block);
        }
        JsonNode root = null;
        for (JsonNode block : blocks) {
            if (block.path("block_type").asInt() == 1) {
                root = block;
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        if (root != null) {
            renderBlock(root, byId, out);
        } else {
            for (JsonNode block : blocks) renderBlock(block, byId, out);
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    private void renderBlock(JsonNode block, Map<String, JsonNode> byId, StringBuilder out) {
        if (block == null || block.isMissingNode()) return;
        int type = block.path("block_type").asInt(-1);
        switch (type) {
            case 1 -> {
                String title = elementsText(block.path("page"));
                if (!title.isBlank()) out.append("# ").append(title).append("\n\n");
                renderChildren(block, byId, out);
            }
            case 2 -> appendText(out, elementsText(block.path("text")), "\n\n");
            case 3, 4, 5, 6, 7, 8, 9, 10, 11 -> {
                String text = elementsText(block.path("heading" + (type - 2)));
                if (!text.isBlank()) {
                    out.append("#".repeat(Math.min(6, type - 2))).append(' ')
                            .append(text).append("\n\n");
                }
            }
            case 12 -> appendText(out, "- " + elementsText(block.path("bullet")), "\n");
            case 13 -> appendText(out, "1. " + elementsText(block.path("ordered")), "\n");
            case 14 -> {
                String code = elementsText(block.path("code"));
                if (!code.isBlank()) out.append("```\n").append(code).append("\n```\n\n");
            }
            case 15 -> appendText(out, "> " + elementsText(block.path("quote")), "\n\n");
            case 17 -> {
                boolean done = block.path("todo").path("style").path("done").asBoolean(false);
                appendText(out, (done ? "- [x] " : "- [ ] ") + elementsText(block.path("todo")), "\n");
            }
            case 22 -> out.append("---\n\n");
            case 31 -> renderTable(block, byId, out);
            case 18 -> out.append("[多维表格]\n\n");
            case 23 -> out.append("[附件]").append("\n\n");
            case 27 -> out.append("[图片]").append("\n\n");
            default -> renderChildren(block, byId, out);
        }
    }

    private void renderChildren(JsonNode block, Map<String, JsonNode> byId, StringBuilder out) {
        for (JsonNode childId : block.path("children")) {
            JsonNode child = byId.get(childId.asText());
            if (child != null) renderBlock(child, byId, out);
        }
    }

    private void renderTable(JsonNode block, Map<String, JsonNode> byId, StringBuilder out) {
        JsonNode cells = block.path("table").path("cells");
        if (cells.size() == 0) cells = block.path("children");
        int columns = Math.max(1, block.path("table").path("property").path("column_size").asInt(1));
        if (cells.size() == 0) {
            renderChildren(block, byId, out);
            return;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode cellId : cells) {
            JsonNode cell = byId.get(cellId.asText());
            values.add(renderTableCell(cell, byId));
        }
        for (int i = 0; i < values.size(); i += columns) {
            int end = Math.min(values.size(), i + columns);
            List<String> row = values.subList(i, end);
            out.append("| ").append(String.join(" | ", row)).append(" |\n");
            if (i == 0) {
                out.append("|");
                for (int c = 0; c < row.size(); c++) out.append(" --- |");
                out.append("\n");
            }
        }
        out.append("\n");
    }

    private String renderTableCell(JsonNode cell, Map<String, JsonNode> byId) {
        if (cell == null) return "";
        StringBuilder value = new StringBuilder();
        renderChildren(cell, byId, value);
        String text = value.toString().trim().replace("\n", "<br>");
        return text.replace("|", "\\|");
    }

    private String elementsText(JsonNode payload) {
        StringBuilder out = new StringBuilder();
        for (JsonNode element : payload.path("elements")) {
            if (element.has("text_run")) {
                JsonNode run = element.path("text_run");
                String content = run.path("content").asText("");
                JsonNode style = run.path("text_element_style");
                if (style.path("inline_code").asBoolean(false)) content = "`" + content + "`";
                if (style.path("bold").asBoolean(false)) content = "**" + content + "**";
                if (style.path("italic").asBoolean(false)) content = "*" + content + "*";
                String url = style.path("link").path("url").asText("");
                if (!url.isBlank()) content = "[" + content + "](" + url + ")";
                out.append(content);
            } else if (element.has("mention")) {
                JsonNode mention = element.path("mention");
                out.append('@').append(mention.path("name").asText(mention.path("user_id").asText("")));
            } else if (element.has("equation")) {
                out.append(element.path("equation").path("content").asText(""));
            }
        }
        return out.toString();
    }

    private void appendText(StringBuilder out, String text, String suffix) {
        if (text != null && !text.isBlank()) out.append(text).append(suffix);
    }

    private Document markdownDocument(String title, String content, String sourceUrl) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String safe = (title == null || title.isBlank() ? "飞书文档" : title)
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("(?i)\\.(md|markdown|txt)$", "");
        return new Document(safe + ".md", bytes, "text/markdown", sourceUrl);
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

    /** 多维表格：每张表的所有记录转成文本，作为独立知识库文档。 */
    private Document fetchBitable(String appToken, String name, String accessToken) throws Exception {
        StringBuilder text = new StringBuilder("# ").append(name == null || name.isBlank() ? "飞书多维表格" : name)
                .append("\n\n");
        String tablePageToken = null;
        int tableCount = 0;
        do {
            String path = "/open-apis/bitable/v1/apps/" + encode(appToken) + "/tables?page_size=100"
                    + (tablePageToken == null ? "" : "&page_token=" + encode(tablePageToken));
            JsonNode data = requestJson(path, accessToken, "GET", null).path("data");
            for (JsonNode table : data.path("items")) {
                tableCount++;
                String tableId = table.path("table_id").asText();
                String tableName = table.path("name").asText("表 " + tableCount);
                text.append("## 表：").append(tableName).append("\n\n");
                String recordPageToken = null;
                int recordIndex = 0;
                do {
                    String recordPath = "/open-apis/bitable/v1/apps/" + encode(appToken)
                            + "/tables/" + encode(tableId) + "/records?page_size=500"
                            + (recordPageToken == null ? "" : "&page_token=" + encode(recordPageToken));
                    JsonNode records = requestJson(recordPath, accessToken, "GET", null).path("data");
                    for (JsonNode record : records.path("items")) {
                        recordIndex++;
                        text.append("### 记录 ").append(recordIndex).append("\n");
                        JsonNode fields = record.path("fields");
                        fields.fields().forEachRemaining(entry -> {
                            String value = fieldText(entry.getValue());
                            if (!value.isBlank()) {
                                text.append("- ").append(entry.getKey()).append(": ")
                                        .append(value).append("\n");
                            }
                        });
                        text.append("\n");
                    }
                    recordPageToken = records.path("page_token").asText(null);
                    if (!records.path("has_more").asBoolean(false)) recordPageToken = null;
                } while (recordPageToken != null && !recordPageToken.isBlank()
                        && !recordPageToken.equals("null"));
            }
            tablePageToken = data.path("page_token").asText(null);
            if (!data.path("has_more").asBoolean(false)) tablePageToken = null;
        } while (tablePageToken != null && !tablePageToken.isBlank() && !tablePageToken.equals("null"));
        if (tableCount == 0) throw new BusinessException(404, "多维表格没有可读取的数据表");
        return textDocument(name, text.toString().trim(), "https://www.feishu.cn/base/" + appToken);
    }

    /** 电子表格：每个工作表读取单元格并转成独立文本，支持文件夹和 /sheets/ 直达链接。 */
    private void addSheet(String spreadsheetToken, String accessToken, String sourceUrl, String fallbackName,
                          List<Document> out, List<String> skipped, Set<String> seen) throws Exception {
        if (!seen.add("sheet:" + spreadsheetToken)) return;
        add(out, fetchSheet(spreadsheetToken, accessToken, sourceUrl, fallbackName));
    }

    private Document fetchSheet(String spreadsheetToken, String accessToken, String sourceUrl,
                                String fallbackName) throws Exception {
        JsonNode data = requestJson("/open-apis/sheets/v3/spreadsheets/" + encode(spreadsheetToken)
                + "/sheets/query", accessToken, "GET", null).path("data");
        StringBuilder text = new StringBuilder();
        int sheetCount = 0;
        for (JsonNode sheet : data.path("sheets")) {
            sheetCount++;
            String sheetId = sheet.path("sheet_id").asText();
            String title = sheet.path("title").asText("Sheet " + sheetCount);
            JsonNode grid = sheet.path("grid_properties");
            int rows = Math.max(1, Math.min(1000, grid.path("row_count").asInt(200)));
            int cols = Math.max(1, Math.min(30, grid.path("column_count").asInt(20)));
            String range = sheetId + "!A1:" + columnName(cols) + rows;
            JsonNode values = requestJson("/open-apis/sheets/v2/spreadsheets/" + encode(spreadsheetToken)
                            + "/values/" + encode(range), accessToken, "GET", null)
                    .path("data").path("valueRange").path("values");
            text.append("## 工作表：").append(title).append("\n\n");
            for (JsonNode row : values) {
                List<String> cells = new ArrayList<>();
                boolean hasValue = false;
                for (JsonNode cell : row) {
                    String value = cellText(cell);
                    cells.add(value);
                    if (!value.isBlank()) hasValue = true;
                }
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
                    cells.remove(cells.size() - 1);
                }
                if (hasValue) {
                    text.append(String.join(" | ", cells)).append("\n");
                }
            }
            text.append("\n");
        }
        if (sheetCount == 0 || text.toString().isBlank()) {
            throw new BusinessException(404, "电子表格没有可读取的数据");
        }
        String name = fallbackName == null || fallbackName.isBlank() ? "飞书表格" : fallbackName;
        return textDocument(name, text.toString().trim(), sourceUrl);
    }

    private String cellText(JsonNode cell) {
        if (cell == null || cell.isNull()) return "";
        if (cell.isValueNode()) return cell.asText("");
        if (cell.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : cell) {
                String part = cellText(item);
                if (!part.isBlank()) parts.add(part);
            }
            return String.join("、", parts);
        }
        for (String key : List.of("text", "name", "link", "url", "value")) {
            JsonNode child = cell.get(key);
            if (child != null && !child.isNull() && child.isValueNode()) {
                String part = child.asText("");
                if (!part.isBlank()) return part;
            }
        }
        return cell.toString();
    }

    private String columnName(int column) {
        StringBuilder name = new StringBuilder();
        int value = Math.max(1, column);
        while (value > 0) {
            value--;
            name.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return name.toString();
    }

    /** 将多维表格字段值（文本、人员、链接、附件数组等）转成可读文本。 */
    private String fieldText(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isValueNode()) return value.asText("");
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : value) {
                String part = fieldText(item);
                if (!part.isBlank() && !parts.contains(part)) parts.add(part);
            }
            return String.join("、", parts);
        }
        StringBuilder out = new StringBuilder();
        for (String key : List.of("text", "name", "en_name", "email", "link", "url", "value")) {
            JsonNode child = value.get(key);
            if (child != null && child.isValueNode()) {
                String part = child.asText("");
                if (!part.isBlank()) {
                    if (out.length() > 0) out.append(' ');
                    out.append(part);
                }
            }
        }
        if (out.length() > 0) return out.toString();
        return value.toString();
    }

    private Document fetchMedia(String token, String name, String accessToken) throws Exception {
        String path = "/open-apis/drive/v1/medias/" + encode(token) + "/download";
        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
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
            JsonNode response = postJson(apiBase + "/open-apis/auth/v3/tenant_access_token/internal",
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
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
