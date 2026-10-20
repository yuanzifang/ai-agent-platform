package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rerank 通用 HTTP 协议：按服务端返回的 index 重排。 */
class RerankServiceTest {

    private HttpServer server;
    private RerankService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", ex -> {
            String body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},"
                    + "{\"index\":0,\"relevance_score\":0.2}]}";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        });
        server.start();
        service = new RerankService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
        ReflectionTestUtils.setField(service, "defaultTopN", 5);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void reordersByServerIndex() {
        List<Map<String, Object>> hits = List.of(
                Map.of("content", "doc-a", "score", 0.8),
                Map.of("content", "doc-b", "score", 0.5));
        List<Map<String, Object>> result = service.rerank("query", hits, 2);
        assertEquals("doc-b", result.get(0).get("content"));
        assertEquals(0.9, (double) result.get(0).get("rerankScore"), 0.0001);
    }
}
