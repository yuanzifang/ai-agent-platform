package com.platform.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 告警 webhook 推送：钉钉加签格式、errcode 判定、故障不抛出。 */
class AlertWebhookServiceTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private AlertWebhookService service(String url, String secret) {
        AlertWebhookService s = new AlertWebhookService(new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(s, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "url", url);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "secret", secret);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "env", "test");
        return s;
    }

    @Test
    void pushesTextMessageWithDingtalkSign() {
        service("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "SECtest").alert("标题", "明细");
        String body = lastBody.get();
        assertTrue(body.contains("msgtype"));
        assertTrue(body.contains("【AI Agent 平台·test】标题"));
        assertTrue(body.contains("timestamp"));
        assertTrue(body.contains("sign"));
    }

    @Test
    void disabledOrBlankUrlIsNoop() {
        assertDoesNotThrow(() -> service("", "").alert("x", null));
        AlertWebhookService s = service("http://127.0.0.1:1/hook", "");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "enabled", false);
        assertDoesNotThrow(() -> s.alert("x", null));
    }

    @Test
    void unreachableEndpointDoesNotThrow() {
        assertDoesNotThrow(() -> service("http://127.0.0.1:1/hook", "").alert("x", null));
    }
}
