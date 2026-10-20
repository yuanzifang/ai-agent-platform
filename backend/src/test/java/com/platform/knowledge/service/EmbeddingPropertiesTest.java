package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingPropertiesTest {

    @Test
    void resolvesConfiguredModels() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("nomic-embed-text");
        props.setDimensions(768);
        props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
        assertEquals(768, props.dimensionOf("nomic-embed-text"));
        assertEquals(1024, props.dimensionOf("bge-m3"));
        assertTrue(props.supports("bge-m3"));
        assertThrows(IllegalArgumentException.class, () -> props.dimensionOf("unknown"));
    }

    @Test
    void defaultModelAlwaysAvailable() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("custom-embed");
        props.setDimensions(512);
        props.setAvailable(Map.of());
        assertEquals(512, props.dimensionOf("custom-embed"));
        assertTrue(props.supports("custom-embed"));
    }
}
