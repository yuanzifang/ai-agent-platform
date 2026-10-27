package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.billing.service.QuotaService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EmbeddingServiceTest {

    @Test
    void batchesLargeEmbeddingRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            int count = mapper.readTree(exchange.getRequestBody()).path("input").size();
            StringBuilder data = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                if (i > 0) data.append(',');
                data.append("{\"index\":").append(i).append(",\"embedding\":[1.0,2.0]}");
            }
            data.append(']');
            byte[] response = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingProperties props = new EmbeddingProperties();
            props.setModel("test-embed");
            props.setDimensions(2);
            props.setAvailable(Map.of("test-embed", 2));
            props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            props.setBatchSize(2);
            EmbeddingService service = new EmbeddingService(props, mock(QuotaService.class));
            ReflectionTestUtils.setField(service, "batchConcurrency", 2);

            float[][] result = service.embedBatch(List.of("a", "b", "c"), "test-embed");

            assertEquals(3, result.length);
            assertEquals(2, result[2].length);
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToNomicWhenBgeIsNotInstalled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"nomic-embed-text:latest\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingProperties props = new EmbeddingProperties();
            props.setProvider("ollama");
            props.setModel("bge-m3");
            props.setDimensions(1024);
            props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
            props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            EmbeddingService service = new EmbeddingService(props, mock(QuotaService.class));

            service.detectAvailableDefaultModel();

            assertEquals("nomic-embed-text", service.model());
            assertEquals(768, service.dimension(service.model()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void prefersBgeWhenInstalled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = ("{\"models\":[{\"name\":\"bge-m3:latest\"},"
                    + "{\"name\":\"nomic-embed-text:latest\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingProperties props = new EmbeddingProperties();
            props.setProvider("ollama");
            props.setModel("bge-m3");
            props.setDimensions(1024);
            props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
            props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            EmbeddingService service = new EmbeddingService(props, mock(QuotaService.class));

            service.detectAvailableDefaultModel();

            assertEquals("bge-m3", service.model());
            assertEquals(1024, service.dimension(service.model()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesConfiguredModels() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("nomic-embed-text");
        props.setDimensions(768);
        props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
        EmbeddingService service = new EmbeddingService(props, mock(QuotaService.class));
        List<Map<String, Object>> models = service.availableModels();
        assertEquals(2, models.size());
        assertTrue(models.stream().anyMatch(m -> "bge-m3".equals(m.get("name"))
                && Integer.valueOf(1024).equals(m.get("dimensions"))));
        assertEquals(1024, service.dimension("bge-m3"));
    }

    @Test
    void supportsHostedOpenAiCompatibleEndpointWithV1Base() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.25]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingProperties props = new EmbeddingProperties();
            props.setProvider("openai");
            props.setModel("hosted-embed");
            props.setDimensions(2);
            props.setAvailable(Map.of("hosted-embed", 2));
            props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            EmbeddingService service = new EmbeddingService(props, mock(QuotaService.class));

            float[] vector = service.embedOne("cloud", "hosted-embed");

            assertEquals(2, vector.length);
            assertEquals(0.5f, vector[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachesRepeatedQueryEmbeddings() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.25]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingProperties props = new EmbeddingProperties();
            props.setProvider("openai");
            props.setModel("hosted-embed");
            props.setDimensions(2);
            props.setAvailable(Map.of("hosted-embed", 2));
            props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            EmbeddingService service = new EmbeddingService(props, mock(QuotaService.class));

            float[] first = service.embedQuery("退款政策是什么", "hosted-embed");
            float[] second = service.embedQuery("退款政策是什么", "hosted-embed");

            assertEquals(2, first.length);
            assertEquals(0.5f, second[0]);
            assertEquals(1, requests.get(), "重复查询应命中进程内缓存");
        } finally {
            server.stop(0);
        }
    }
}
