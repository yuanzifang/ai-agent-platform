package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 嵌入结果缓存：按 embedding_model + SHA256(embeddingText) 复用向量。
 * 缓存不可用时自动回退全量嵌入；缓存读写失败不阻断文档解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeEmbeddingCache {

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Qualifier("jdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Value("${embedding.cache-ttl-days:30}")
    private int embeddingCacheTtlDays = 30;
    @Value("${embedding.cache-max-entries:100000}")
    private int embeddingCacheMaxEntries = 100000;
    private final AtomicLong lastCleanupAt = new AtomicLong(0);

    public float[][] embedWithCache(List<DocumentParser.ParsedChunk> chunks, String model) {
        cleanupIfNeeded();
        List<String> texts = chunks.stream()
                .map(DocumentParser.ParsedChunk::embeddingContent).toList();
        if (mysqlJdbcTemplate == null || texts.isEmpty()) {
            return embeddingService.embedBatch(texts, model);
        }
        // 缓存键始终使用 contentHash；不能把原始文本当作 hash 查缓存，否则永远 miss。
        LinkedHashMap<String, String> uniqueByHash = new LinkedHashMap<>();
        for (String text : texts) uniqueByHash.putIfAbsent(contentHash(text), text);
        Map<String, float[]> cached = load(model, uniqueByHash.keySet());
        List<String> missing = uniqueByHash.entrySet().stream()
                .filter(entry -> !cached.containsKey(entry.getKey()))
                .map(Map.Entry::getValue).toList();
        if (texts.size() > 200) {
            log.info("embedding 缓存：chunks={} unique={} hits={} missing={}",
                    texts.size(), uniqueByHash.size(), cached.size(), missing.size());
        }
        if (!missing.isEmpty()) {
            float[][] generated = embeddingService.embedBatch(missing, model);
            for (int i = 0; i < missing.size(); i++) {
                cached.put(contentHash(missing.get(i)), generated[i]);
            }
            store(model, missing, generated);
        }
        float[][] vectors = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            vectors[i] = cached.get(contentHash(texts.get(i)));
        }
        return vectors;
    }

    private void cleanupIfNeeded() {
        if (mysqlJdbcTemplate == null) return;
        long now = System.currentTimeMillis();
        long last = lastCleanupAt.get();
        if (now - last < 3_600_000L || !lastCleanupAt.compareAndSet(last, now)) return;
        try {
            int days = Math.max(1, embeddingCacheTtlDays);
            mysqlJdbcTemplate.update("DELETE FROM knowledge_embedding_cache WHERE created_at < " +
                    "DATE_SUB(NOW(), INTERVAL " + days + " DAY) LIMIT 10000");
            int maxEntries = Math.max(1000, embeddingCacheMaxEntries);
            mysqlJdbcTemplate.update("DELETE FROM knowledge_embedding_cache WHERE id IN (" +
                    "SELECT id FROM (SELECT id FROM knowledge_embedding_cache " +
                    "ORDER BY created_at DESC LIMIT " + maxEntries + ", 10000) t)");
        } catch (Exception e) {
            log.debug("embedding 缓存清理失败（忽略）：{}", e.getMessage());
        }
    }

    private Map<String, float[]> load(String model, Collection<String> hashes) {
        Map<String, float[]> cached = new HashMap<>();
        if (hashes == null || hashes.isEmpty()) return cached;
        try {
            List<String> hashList = new ArrayList<>(hashes);
            for (int start = 0; start < hashList.size(); start += 500) {
                List<String> batch = hashList.subList(start, Math.min(hashList.size(), start + 500));
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(model);
                args.addAll(batch);
                List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                        "SELECT content_hash, embedding FROM knowledge_embedding_cache " +
                                "WHERE embedding_model = ? AND content_hash IN (" + placeholders + ")",
                        args.toArray());
                for (Map<String, Object> row : rows) {
                    Object hash = row.get("content_hash");
                    Object embedding = row.get("embedding");
                    if (hash == null || embedding == null) continue;
                    try {
                        cached.put(String.valueOf(hash),
                                objectMapper.readValue(String.valueOf(embedding), float[].class));
                    } catch (Exception ignored) {
                        // 缓存损坏时按 miss 处理
                    }
                }
            }
        } catch (Exception e) {
            log.warn("embedding 缓存读取失败，回退全量嵌入：{}", e.getMessage());
        }
        return cached;
    }

    private void store(String model, List<String> texts, float[][] vectors) {
        if (texts.isEmpty()) return;
        try {
            String sql = "INSERT INTO knowledge_embedding_cache " +
                    "(embedding_model, content_hash, dimensions, embedding) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE dimensions = VALUES(dimensions), embedding = VALUES(embedding)";
            List<Object[]> batch = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                batch.add(new Object[]{model, contentHash(texts.get(i)), vectors[i].length,
                        objectMapper.writeValueAsString(vectors[i])});
            }
            for (int start = 0; start < batch.size(); start += 500) {
                mysqlJdbcTemplate.batchUpdate(sql,
                        batch.subList(start, Math.min(batch.size(), start + 500)));
            }
        } catch (Exception e) {
            log.warn("embedding 缓存写入失败（不影响解析）：{}", e.getMessage());
        }
    }

    private String contentHash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(text).hashCode());
        }
    }
}
