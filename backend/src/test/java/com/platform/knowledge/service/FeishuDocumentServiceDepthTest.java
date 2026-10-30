package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuDocumentServiceDepthTest {

    private HttpServer server;
    private FeishuDocumentService service;
    private final AtomicBoolean fourthLevelRequested = new AtomicBoolean(false);
    private final AtomicBoolean fourthLinkedDocRequested = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        service = new FeishuDocumentService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "apiBase",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void stopsFolderRecursionAfterThreeLevels() {
        FeishuDocumentService.Collection result = service.collectDetailed(
                "https://team.feishu.cn/drive/folder/root", "app", "secret");

        assertEquals(List.of("d1.txt", "d2.txt", "d3.txt"),
                result.documents().stream().map(FeishuDocumentService.Document::name).toList());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).contains("超过三层嵌套"));
        assertFalse(fourthLevelRequested.get(), "第四层文件夹不应被请求");
    }

    @Test
    void followsDocxHyperlinksUpToThreeLevels() {
        FeishuDocumentService.Collection result = service.collectDetailed(
                "https://team.feishu.cn/docx/l1", "app", "secret");

        assertEquals(3, result.documents().size());
        assertTrue(result.documents().stream()
                .anyMatch(d -> "https://team.feishu.cn/docx/l1".equals(d.sourceUrl())));
        assertTrue(result.documents().stream()
                .anyMatch(d -> "https://team.feishu.cn/docx/l2".equals(d.sourceUrl())));
        assertTrue(result.documents().stream()
                .anyMatch(d -> "https://team.feishu.cn/docx/l3".equals(d.sourceUrl())));
        assertFalse(fourthLinkedDocRequested.get(), "第四层超链接文档不应被请求");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body;
        if ("/open-apis/auth/v3/tenant_access_token/internal".equals(path)) {
            body = "{\"code\":0,\"tenant_access_token\":\"test-token\"}";
        } else if ("/open-apis/drive/v1/files".equals(path)) {
            String folder = param(query, "folder_token");
            body = switch (folder) {
                case "root" -> files("{\"type\":\"docx\",\"token\":\"d1\",\"name\":\"d1\"}",
                        "{\"type\":\"folder\",\"token\":\"f2\",\"name\":\"f2\"}");
                case "f2" -> files("{\"type\":\"docx\",\"token\":\"d2\",\"name\":\"d2\"}",
                        "{\"type\":\"folder\",\"token\":\"f3\",\"name\":\"f3\"}");
                case "f3" -> files("{\"type\":\"docx\",\"token\":\"d3\",\"name\":\"d3\"}",
                        "{\"type\":\"folder\",\"token\":\"f4\",\"name\":\"f4\"}");
                default -> {
                    if ("f4".equals(folder)) fourthLevelRequested.set(true);
                    yield "{\"code\":0,\"data\":{\"files\":[]}}";
                }
            };
        } else if (path.startsWith("/open-apis/docx/v1/documents/") && path.endsWith("/blocks")) {
            String token = path.substring("/open-apis/docx/v1/documents/".length(),
                    path.length() - "/blocks".length());
            if ("l4".equals(token)) fourthLinkedDocRequested.set(true);
            body = switch (token) {
                case "l1" -> blocks(link("l2"));
                case "l2" -> blocks(link("l3"));
                case "l3" -> blocks(link("l4"));
                default -> "{\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}";
            };
        } else if (path.startsWith("/open-apis/docx/v1/documents/") && path.endsWith("/raw_content")) {
            String token = path.substring("/open-apis/docx/v1/documents/".length(),
                    path.length() - "/raw_content".length());
            if ("l4".equals(token)) fourthLinkedDocRequested.set(true);
            body = "{\"code\":0,\"data\":{\"content\":\"content-" + token + "\"}}";
        } else {
            body = "{\"code\":404,\"msg\":\"not found\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String files(String... items) {
        return "{\"code\":0,\"data\":{\"files\":[" + String.join(",", items) + "],\"has_more\":false}}";
    }

    private String blocks(String item) {
        return "{\"code\":0,\"data\":{\"items\":[" + item + "],\"has_more\":false}}";
    }

    private String link(String token) {
        return "{\"block_type\":2,\"text\":{\"elements\":[{\"text_run\":{\"content\":\"link\","
                + "\"text_element_style\":{\"link\":{\"url\":\"https://team.feishu.cn/docx/"
                + token + "\"}}}}]}}";
    }

    private String param(String query, String key) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && key.equals(part.substring(0, eq))) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
