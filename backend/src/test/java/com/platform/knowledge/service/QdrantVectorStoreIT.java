package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选 Qdrant 实联调：-Dqdrant.it=true，默认 Qdrant 在 127.0.0.1:56333。 */
@EnabledIfSystemProperty(named = "qdrant.it", matches = "true")
class QdrantVectorStoreIT {

    @Test
    void savesAndSearchesWithAcl() throws Exception {
        String base = System.getProperty("qdrant.url", "http://127.0.0.1:56333");
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/collections"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Qdrant 不可用: " + e.getMessage());
        }
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("it-model");
        props.setDimensions(4);
        props.setAvailable(Map.of("it-model", 4));
        QdrantVectorStore store = new QdrantVectorStore(props, new ObjectMapper());
        ReflectionTestUtils.setField(store, "baseUrl", base);
        ReflectionTestUtils.setField(store, "collectionPrefix", "aap_it");
        long tenant = 990001L;
        store.ensureTenantSchema(tenant);
        try {
            store.saveBatch(tenant, List.of(Map.of(
                    "content", "qdrant content",
                    "embedding", new float[]{1, 0, 0, 0},
                    "metadata", "{\"knowledge_base_id\":\"1\",\"chunk_id\":\"1\","
                            + "\"document_id\":\"1\",\"chunk_index\":0,"
                            + "\"allowed_users\":[\"2\"],\"tenant_visible\":false}")), 4);

            var user2 = store.search(tenant, new float[]{1, 0, 0, 0}, 1L, 5, 0.1, 4, 2L, false);
            var user9 = store.search(tenant, new float[]{1, 0, 0, 0}, 1L, 5, 0.1, 4, 9L, false);
            assertEquals(1, user2.size());
            assertTrue(String.valueOf(user2.get(0).get("content")).contains("qdrant"));
            assertEquals(0, user9.size());
            assertEquals(1, store.countByDocument(tenant, 1L, 4));
            store.deleteByKnowledgeBase(tenant, 1L);
            assertEquals(0, store.countByDocument(tenant, 1L, 4));
        } finally {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create(base + "/collections/aap_it_" + tenant + "_4"))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
