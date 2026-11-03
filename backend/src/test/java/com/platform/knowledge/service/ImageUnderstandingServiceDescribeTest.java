package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** describe/describeForChat：聊天与知识库两条视觉描述链路的开关、模型选择与响应处理。 */
class ImageUnderstandingServiceDescribeTest {

    private ImageUnderstandingService service(HttpServer server, boolean enabled, String model) {
        ImageUnderstandingService service = new ImageUnderstandingService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", model);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10);
        return service;
    }

    @Test
    void describeForChatDisabledReturnsEmptyWithoutCallingEndpoint() {
        ImageUnderstandingService service = new ImageUnderstandingService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals("", service.describeForChat(new byte[]{1, 2, 3}, "a.png"));
    }

    @Test
    void describeForChatUsesGenericPromptWithConfiguredModel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = mockOllama(requestBody, "图片描述");
        try {
            ImageUnderstandingService service = service(server, true, "mock-vl");
            assertEquals("图片描述", service.describeForChat(new byte[]{1}, "photo.png"));

            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertEquals("mock-vl", body.path("model").asText());
            assertTrue(body.path("prompt").asText().contains("图片"),
                    "默认聊天识别 prompt 应为平台通用提示");
            assertEquals("AQ==", body.path("images").get(0).asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void describeForChatUsesAgentLevelPromptOverride() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = mockOllama(requestBody, "自定义描述");
        try {
            ImageUnderstandingService service = service(server, true, "mock-vl");
            assertEquals("自定义描述",
                    service.describeForChat(new byte[]{1}, "photo.png", "识别图片中的作物病斑"));

            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertEquals("识别图片中的作物病斑", body.path("prompt").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void describeHonorsParseOptionOverridesOverGlobalSwitch() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = mockOllama(requestBody, "kb 描述");
        try {
            // 全局关闭，但知识库级覆盖打开并指定模型
            ImageUnderstandingService service = service(server, false, "global-vl");
            String out = service.describe(new byte[]{1}, "chart.png",
                    ParseOptions.defaults(false, null, null, 200, 5, true, null, "kb-vl", 3));
            assertEquals("kb 描述", out);
            assertEquals("kb-vl", new ObjectMapper().readTree(requestBody.get()).path("model").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void describeGloballyDisabledWithoutOverridesReturnsEmpty() {
        ImageUnderstandingService service = new ImageUnderstandingService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals("", service.describe(new byte[]{1}, "a.png"));
    }

    @Test
    void emptyBytesReturnEmptyWithoutHttp() throws Exception {
        HttpServer server = mockOllama(new AtomicReference<>(), "不应被调用");
        try {
            ImageUnderstandingService service = service(server, true, "m");
            assertEquals("", service.describeForChat(new byte[0], "a.png"));
            assertEquals("", service.describe(null, "a.png"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void non200ResponseReturnsEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            ImageUnderstandingService service = service(server, true, "m");
            assertEquals("", service.describeForChat(new byte[]{1}, "a.png"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void longDescriptionTruncatedTo800Chars() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            String longText = "z".repeat(900);
            String response = new ObjectMapper().writeValueAsString(java.util.Map.of("response", longText));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ImageUnderstandingService service = service(server, true, "m");
            String out = service.describeForChat(new byte[]{1}, "a.png");
            assertEquals(800, out.length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maxImagesClampedToSaneRange() {
        ImageUnderstandingService service = new ImageUnderstandingService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxImages", 0);
        assertEquals(1, service.maxImages());
        ReflectionTestUtils.setField(service, "maxImages", 99);
        assertEquals(10, service.maxImages());
        ReflectionTestUtils.setField(service, "maxImages", 3);
        assertEquals(3, service.maxImages());
    }

    private HttpServer mockOllama(AtomicReference<String> requestBody, String text) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = new ObjectMapper().writeValueAsString(java.util.Map.of("response", text));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
