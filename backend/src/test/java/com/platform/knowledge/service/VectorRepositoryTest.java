package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class VectorRepositoryTest {

    @Test
    void usesSeparateTablePerDimension() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("nomic-embed-text");
        props.setDimensions(768);
        props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
        VectorRepository repo = new VectorRepository(mock(JdbcTemplate.class), props);
        assertEquals("embeddings", repo.table(768));
        assertEquals("embeddings_1024", repo.table(1024));
    }
}
