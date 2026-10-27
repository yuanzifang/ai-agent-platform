package com.platform.knowledge.service;

import com.platform.billing.service.QuotaService;
import com.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * 嵌入服务（架构 6.4）：Ollama/OpenAI 兼容 /v1/embeddings；知识库按名称选择模型与维度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingProperties properties;
    private final QuotaService quotaService;

    /** 启动探测后的实际默认模型；未探测/探测失败时回退配置值。 */
    private volatile String effectiveModel;

    @org.springframework.beans.factory.annotation.Value("${embedding.batch-concurrency:1}")
    private int batchConcurrency = 1;

    @org.springframework.beans.factory.annotation.Value("${embedding.query-cache-ttl-seconds:300}")
    private long queryCacheTtlSeconds = 300;

    @org.springframework.beans.factory.annotation.Value("${embedding.query-cache-max-entries:2000}")
    private int queryCacheMaxEntries = 2000;

    @org.springframework.beans.factory.annotation.Value("${embedding.query-cache-redis-enabled:true}")
    private boolean redisQueryCacheEnabled = true;

    @org.springframework.beans.factory.annotation.Value("${embedding.query-cache-redis-lock-enabled:true}")
    private boolean redisLockEnabled = true;

    @org.springframework.beans.factory.annotation.Value("${embedding.query-cache-redis-lock-wait-ms:3000}")
    private long redisLockWaitMs = 3000;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 测试/无 Redis 环境可缺省；有 Redis 时跨实例共享 query embedding。 */
    @Autowired(required = false)
    private StringRedisTemplate redis;

    /** 查询 embedding 进程内缓存：重复 FAQ/检索问句不必反复打 embedding 上游。 */
    private record QueryCacheEntry(float[] vector, long expiresAt) { }
    private final Map<String, QueryCacheEntry> queryCache = new ConcurrentHashMap<>();
    /** 单飞：同一模型/问句的冷查询只允许一个请求打 embedding 上游。 */
    private final Map<String, CompletableFuture<float[]>> queryInFlight = new ConcurrentHashMap<>();

    private volatile RestClient client;
    private volatile ExecutorService embedExecutor;

    private ExecutorService executor() {
        if (embedExecutor == null) {
            synchronized (this) {
                if (embedExecutor == null) {
                    int threads = Math.max(1, Math.min(4, batchConcurrency));
                    embedExecutor = Executors.newFixedThreadPool(threads, runnable -> {
                        Thread thread = new Thread(runnable, "embedding-batch");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return embedExecutor;
    }

    @PreDestroy
    void shutdownExecutor() {
        if (embedExecutor != null) embedExecutor.shutdownNow();
    }

    /**
     * 默认优先 bge-m3；如果 Ollama 未安装 bge-m3 但装了 nomic-embed-text，自动回退，
     * 避免中文默认模型切换导致旧环境新知识库直接不可用。
     */
    @PostConstruct
    void detectAvailableDefaultModel() {
        String configured = properties.getModel();
        effectiveModel = configured;
        if (!"ollama".equalsIgnoreCase(properties.getProvider())) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client().get().uri("/api/tags").retrieve().body(Map.class);
            List<Map<String, Object>> models = body == null ? null
                    : (List<Map<String, Object>>) body.get("models");
            if (models == null) return;
            Set<String> installed = new java.util.HashSet<>();
            for (Map<String, Object> item : models) {
                String name = String.valueOf(item.get("name"));
                installed.add(name);
                installed.add(name.replace(":latest", ""));
            }
            String chosen = installed.contains(configured) ? configured : null;
            if (chosen == null && installed.contains("bge-m3")) chosen = "bge-m3";
            if (chosen == null && installed.contains("nomic-embed-text")) chosen = "nomic-embed-text";
            if (chosen != null && !chosen.equals(configured)) {
                log.warn("默认 embedding 模型 {} 未安装，自动回退 {}", configured, chosen);
            }
            if (chosen != null) {
                effectiveModel = chosen;
                properties.setModel(chosen);
                Integer dimension = properties.models().get(chosen);
                if (dimension == null) {
                    dimension = "bge-m3".equals(chosen) ? 1024
                            : "nomic-embed-text".equals(chosen) ? 768
                            : properties.getDimensions();
                    properties.getAvailable().putIfAbsent(chosen, dimension);
                }
                properties.setDimensions(dimension);
            }
        } catch (Exception e) {
            log.warn("Ollama 模型探测失败，保持默认模型 {}：{}", configured, e.getMessage());
        }
    }

    private RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(120));
            client = RestClient.builder()
                    .baseUrl(properties.getEndpoint() == null ? "" : properties.getEndpoint().replaceAll("/+$", ""))
                    .requestFactory(factory).build();
        }
        return client;
    }

    /** OpenAI 兼容基址可能已带 /v1；避免拼成 /v1/v1/embeddings。 */
    private String embeddingsPath() {
        String endpoint = properties.getEndpoint() == null ? "" : properties.getEndpoint().replaceAll("/+$", "");
        return endpoint.endsWith("/v1") ? "/embeddings" : "/v1/embeddings";
    }

    public float[] embedOne(String text) {
        return embedBatch(List.of(text), model())[0];
    }

    public float[] embedOne(String text, String modelName) {
        return embedBatch(List.of(text), modelName)[0];
    }

    /** 检索问句 embedding 缓存；只缓存单条查询，不缓存文档批量入库。 */
    public float[] embedQuery(String text, String modelName) {
        if (text == null || text.isBlank() || queryCacheTtlSeconds <= 0 || queryCacheMaxEntries <= 0) {
            return embedOne(text, modelName);
        }
        String model = modelName == null || modelName.isBlank() ? model() : modelName;
        String key = model + ":" + sha256(text);
        long now = System.currentTimeMillis();
        QueryCacheEntry cached = queryCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.vector();
        }
        if (redisQueryCacheEnabled && redis != null) {
            float[] shared = redisGet(key);
            if (shared != null) {
                putLocal(key, shared, now);
                return shared;
            }
        }
        CompletableFuture<float[]> mine = new CompletableFuture<>();
        CompletableFuture<float[]> existing = queryInFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return awaitLocal(existing);
        }
        String lockToken = null;
        if (redisQueryCacheEnabled && redisLockEnabled && redis != null) {
            lockToken = tryLock(key);
            if (lockToken == null) {
                float[] shared = awaitRedis(key);
                if (shared != null) {
                    putLocal(key, shared, now);
                    mine.complete(shared);
                    queryInFlight.remove(key, mine);
                    return shared;
                }
            }
        }
        try {
            float[] vector = embedOne(text, model);
            if (vector != null) {
                putLocal(key, vector, now);
                redisPut(key, vector);
            }
            mine.complete(vector);
            return vector;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            if (lockToken != null) unlock(key, lockToken);
            queryInFlight.remove(key, mine);
        }
    }

    private float[] awaitLocal(CompletableFuture<float[]> future) {
        try {
            return future.get(130, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "查询 embedding 等待被中断");
        } catch (ExecutionException e) {
            Throwable root = e.getCause() == null ? e : e.getCause();
            if (root instanceof BusinessException business) throw business;
            throw new BusinessException(502, "查询 embedding 失败：" + root.getMessage());
        } catch (TimeoutException e) {
            throw new BusinessException(504, "查询 embedding 等待超时");
        }
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent("embedding:lock:" + key, token,
                    Duration.ofSeconds(30));
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            log.debug("获取 Redis embedding 锁失败（回退计算）：{}", e.getMessage());
            return null;
        }
    }

    private void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList("embedding:lock:" + key), token);
        } catch (Exception e) {
            log.debug("释放 Redis embedding 锁失败（等待 TTL）：{}", e.getMessage());
        }
    }

    private float[] awaitRedis(String key) {
        long deadline = System.currentTimeMillis() + Math.max(0, redisLockWaitMs);
        while (System.currentTimeMillis() < deadline) {
            float[] cached = redisGet(key);
            if (cached != null) return cached;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void putLocal(String key, float[] vector, long now) {
        if (queryCache.size() >= queryCacheMaxEntries) {
            queryCache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
            if (queryCache.size() >= queryCacheMaxEntries) queryCache.clear();
        }
        queryCache.put(key, new QueryCacheEntry(vector, now + queryCacheTtlSeconds * 1000L));
    }

    private float[] redisGet(String key) {
        try {
            String value = redis.opsForValue().get("embedding:q:" + key);
            return value == null ? null : decodeVector(value);
        } catch (Exception e) {
            log.debug("读取 Redis query embedding 缓存失败：{}", e.getMessage());
            return null;
        }
    }

    private void redisPut(String key, float[] vector) {
        if (queryCacheTtlSeconds <= 0) return;
        try {
            redis.opsForValue().set("embedding:q:" + key, encodeVector(vector),
                    Duration.ofSeconds(queryCacheTtlSeconds));
        } catch (Exception e) {
            log.debug("写入 Redis query embedding 缓存失败：{}", e.getMessage());
        }
    }

    private static String encodeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) buffer.putFloat(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static float[] decodeVector(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length % Float.BYTES != 0) return null;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            float[] vector = new float[bytes.length / Float.BYTES];
            for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
            return vector;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    public float[][] embedBatch(List<String> texts) {
        return embedBatch(texts, model());
    }

    public float[][] embedBatch(List<String> texts, String modelName) {
        String effectiveModel = modelName == null || modelName.isBlank()
                ? properties.getModel() : modelName;
        if (!properties.supports(effectiveModel)) {
            throw new BusinessException(400, "未配置的 embedding 模型：" + effectiveModel);
        }
        if (texts == null || texts.isEmpty()) return new float[0][];
        long chars = texts.stream().mapToLong(t -> t == null ? 0 : t.length()).sum();
        quotaService.checkTokensToday(Math.max(50, chars / 2));
        int batchSize = Math.max(1, Math.min(properties.getBatchSize(), 128));
        if (texts.size() <= batchSize) return requestBatch(texts, effectiveModel);
        float[][] result = new float[texts.size()][];
        List<Integer> starts = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) starts.add(start);
        if (batchConcurrency <= 1 || starts.size() <= 1) {
            for (int start : starts) {
                int end = Math.min(texts.size(), start + batchSize);
                float[][] part = requestBatch(texts.subList(start, end), effectiveModel);
                System.arraycopy(part, 0, result, start, part.length);
            }
            return result;
        }
        List<Future<float[][]>> futures = new ArrayList<>(starts.size());
        for (int start : starts) {
            int end = Math.min(texts.size(), start + batchSize);
            futures.add(executor().submit(() -> requestBatch(texts.subList(start, end), effectiveModel)));
        }
        for (int i = 0; i < starts.size(); i++) {
            try {
                float[][] part = futures.get(i).get();
                System.arraycopy(part, 0, result, starts.get(i), part.length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(502, "embedding 批次被中断");
            } catch (ExecutionException e) {
                Throwable root = e.getCause() == null ? e : e.getCause();
                if (root instanceof BusinessException business) throw business;
                throw new BusinessException(502, "embedding 批次失败：" + root.getMessage());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private float[][] requestBatch(List<String> texts, String effectiveModel) {
        int expectedDim = properties.dimensionOf(effectiveModel);
        try {
            Map<String, Object> resp = client().post()
                    .uri(embeddingsPath())
                    .body(Map.of("model", effectiveModel, "input", texts))
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = resp == null ? null : (List<Map<String, Object>>) resp.get("data");
            if (data == null || data.size() != texts.size()) {
                throw new BusinessException(502, "嵌入服务返回异常");
            }
            float[][] result = new float[texts.size()][];
            for (Map<String, Object> item : data) {
                int index = ((Number) item.get("index")).intValue();
                List<Number> vec = (List<Number>) item.get("embedding");
                if (vec.size() != expectedDim) {
                    throw new BusinessException(502, "embedding 维度不匹配：模型 " + effectiveModel
                            + " 期望 " + expectedDim + "，返回 " + vec.size());
                }
                result[index] = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) result[index][i] = vec.get(i).floatValue();
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            throw new BusinessException(502, "嵌入服务调用失败（" + effectiveModel + "）：" + root.getMessage());
        }
    }

    public String model() {
        return effectiveModel == null || effectiveModel.isBlank()
                ? properties.getModel() : effectiveModel;
    }

    public int dimension(String modelName) {
        return properties.dimensionOf(modelName == null || modelName.isBlank() ? model() : modelName);
    }

    public boolean supports(String modelName) {
        return properties.supports(modelName);
    }

    public List<Map<String, Object>> availableModels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : properties.models().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", e.getKey());
            item.put("dimensions", e.getValue());
            item.put("default", e.getKey().equals(properties.getModel()));
            list.add(item);
        }
        return list;
    }
}
