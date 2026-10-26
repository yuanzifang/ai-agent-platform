package com.platform.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertWebhookServiceTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>("{\"errcode\":0}");
    private int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] out = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private AlertWebhookService service(String path, String secret, boolean enabled) {
        AlertWebhookService service = new AlertWebhookService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "url", "http://127.0.0.1:" + server.getAddress().getPort() + path);
        ReflectionTestUtils.setField(service, "secret", secret);
        ReflectionTestUtils.setField(service, "env", "test");
        return service;
    }

    @Test
    void deliversTextPayload() {
        AlertWebhookService service = service("/hook", "", true);

        assertTrue(service.alert("数据库连接数告警", "活跃连接 95%"));

        String sent = body.get();
        assertTrue(sent.contains("\"msgtype\":\"text\""));
        assertTrue(sent.contains("【AI Agent 平台·test】数据库连接数告警"));
        assertTrue(sent.contains("活跃连接 95%"));
        assertNull(query.get());
    }

    @Test
    void disabledDoesNotRequest() {
        AlertWebhookService service = service("/hook", "", false);

        assertFalse(service.alert("x", "y"));
        assertFalse(service.isConfigured());
        assertNull(body.get());
    }

    @Test
    void dingTalkSignatureIsPutInQuery() {
        AlertWebhookService service = service("/dingtalk/robot/send?access_token=abc", "ding-secret", true);

        assertTrue(service.alert("钉钉测试", "签名校验"));

        String sentQuery = query.get();
        assertTrue(sentQuery.contains("access_token=abc"));
        assertTrue(sentQuery.contains("timestamp="));
        assertTrue(sentQuery.contains("sign="));
        assertFalse(body.get().contains("\"sign\""));
        assertFalse(body.get().contains("\"timestamp\""));
    }

    @Test
    void feishuCodeZeroIsSuccess() {
        response.set("{\"code\":0,\"msg\":\"success\"}");
        AlertWebhookService service = service("/hook", "", true);

        assertTrue(service.alert("飞书测试", ""));

        response.set("{\"code\":19001,\"msg\":\"invalid\"}");
        assertFalse(service.alert("飞书失败", ""));
    }

    @Test
    void httpErrorDoesNotThrow() {
        status = 500;
        response.set("{\"error\":\"down\"}");
        AlertWebhookService service = service("/hook", "", true);

        assertFalse(service.alert("失败测试", ""));
    }
}
