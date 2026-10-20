package com.platform.knowledge.service;

import com.platform.billing.service.QuotaService;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入服务（架构 6.4）：Ollama/OpenAI 兼容 /v1/embeddings；知识库按名称选择模型与维度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingProperties properties;
    private final QuotaService quotaService;

    private RestClient client;

    private RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(120));
            client = RestClient.builder().baseUrl(properties.getEndpoint())
                    .requestFactory(factory).build();
        }
        return client;
    }

    public float[] embedOne(String text) {
        return embedBatch(List.of(text), properties.getModel())[0];
    }

    public float[] embedOne(String text, String modelName) {
        return embedBatch(List.of(text), modelName)[0];
    }

    public float[][] embedBatch(List<String> texts) {
        return embedBatch(texts, properties.getModel());
    }

    @SuppressWarnings("unchecked")
    public float[][] embedBatch(List<String> texts, String modelName) {
        String effectiveModel = modelName == null || modelName.isBlank()
                ? properties.getModel() : modelName;
        if (!properties.supports(effectiveModel)) {
            throw new BusinessException(400, "未配置的 embedding 模型：" + effectiveModel);
        }
        int expectedDim = properties.dimensionOf(effectiveModel);
        if (texts == null || texts.isEmpty()) return new float[0][];
        long chars = texts.stream().mapToLong(t -> t == null ? 0 : t.length()).sum();
        quotaService.checkTokensToday(Math.max(50, chars / 2));
        try {
            Map<String, Object> resp = client().post()
                    .uri("/v1/embeddings")
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
        return properties.getModel();
    }

    public int dimension(String modelName) {
        return properties.dimensionOf(modelName);
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
