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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionTypeClassifierTest {

    private HttpServer server;
    private SectionTypeClassifier classifier;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            String body = "{\"message\":{\"content\":\"[{\\\"index\\\":1,\\\"section\\\":\\\"action\\\"},"
                    + "{\\\"index\\\":2,\\\"section\\\":\\\"forecast\\\"}]\"}}";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        });
        server.start();
        classifier = new SectionTypeClassifier(new ObjectMapper());
        ReflectionTestUtils.setField(classifier, "enabled", true);
        ReflectionTestUtils.setField(classifier, "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(classifier, "model", "test-model");
        ReflectionTestUtils.setField(classifier, "batchSize", 10);
        ReflectionTestUtils.setField(classifier, "timeoutSeconds", 5);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void classifiesChunksByModelJson() {
        var chunks = List.of(
                new DocumentParser.ParsedChunk("水稻稻瘟病防治：破口前用三环唑。", 0, "", null, 1, 1, List.of()),
                new DocumentParser.ParsedChunk("水稻稻瘟病发生趋势预测。", 0, "", null, 1, 1, List.of()));

        Map<Integer, String> result = classifier.classify(chunks);

        assertEquals("action", result.get(0));
        assertEquals("forecast", result.get(1));
    }

    @Test
    void normalizesChineseModelLabels() {
        assertEquals("action", SectionTypeClassifier.normalizeSection("防治措施"));
        assertEquals("forecast", SectionTypeClassifier.normalizeSection("预测/趋势"));
        assertEquals("background", SectionTypeClassifier.normalizeSection("症状/危害"));
        assertEquals("table", SectionTypeClassifier.normalizeSection("表格"));
    }

    @Test
    void disabledClassifierReturnsEmptyMap() {
        ReflectionTestUtils.setField(classifier, "enabled", false);
        var chunks = List.of(new DocumentParser.ParsedChunk("正文", 0, "", null, 1, 1, List.of()));
        assertTrue(classifier.classify(chunks).isEmpty());
    }
}
