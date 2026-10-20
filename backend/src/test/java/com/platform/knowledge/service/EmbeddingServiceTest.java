package com.platform.knowledge.service;

import com.platform.billing.service.QuotaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EmbeddingServiceTest {

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
}
