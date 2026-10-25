package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageUnderstandingServiceChartTest {

    @Test
    void parsesStructuredChartJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            String response = "{\"response\":\"{\\\"isChart\\\":true,\\\"title\\\":\\\"Quarterly Sales\\\","
                    + "\\\"xAxis\\\":\\\"Quarter\\\",\\\"yAxis\\\":\\\"Revenue\\\","
                    + "\\\"series\\\":[{\\\"name\\\":\\\"Sales\\\",\\\"points\\\":[{\\\"label\\\":\\\"Q1\\\",\\\"value\\\":\\\"120\\\"}]}]}\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ImageUnderstandingService service = new ImageUnderstandingService(new ObjectMapper());
            ReflectionTestUtils.setField(service, "enabled", true);
            ReflectionTestUtils.setField(service, "endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "model", "mock-vl");
            ReflectionTestUtils.setField(service, "timeoutSeconds", 10);

            var chart = service.extractChartData(new byte[]{1, 2, 3}, "png", null);
            assertNotNull(chart);
            assertEquals("Quarterly Sales", chart.path("title").asText());
            assertEquals("Q1", chart.path("series").get(0).path("points").get(0).path("label").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsNullForNonChart() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] bytes = "{\"response\":\"{\\\"isChart\\\":false}\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ImageUnderstandingService service = new ImageUnderstandingService(new ObjectMapper());
            ReflectionTestUtils.setField(service, "enabled", true);
            ReflectionTestUtils.setField(service, "endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "timeoutSeconds", 10);
            assertNull(service.extractChartData(new byte[]{1}, "png", null));
        } finally {
            server.stop(0);
        }
    }
}
