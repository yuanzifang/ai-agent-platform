package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 嵌入服务（架构 6.4）：本地 Ollama nomic-embed-text，OpenAI 兼容 /v1/embeddings，768 维，零成本。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final RestClient client;
    private final String model;

    public EmbeddingService(@Value("${embedding.endpoint}") String endpoint,
                            @Value("${embedding.model}") String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().baseUrl(endpoint).requestFactory(factory).build();
        this.model = model;
    }

    public float[] embedOne(String text) {
        return embedBatch(List.of(text))[0];
    }

    @SuppressWarnings("unchecked")
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return new float[0][];
        try {
            Map<String, Object> resp = client.post()
                    .uri("/v1/embeddings")
                    .body(Map.of("model", model, "input", texts))
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
                result[index] = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) result[index][i] = vec.get(i).floatValue();
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            throw new BusinessException(502, "嵌入服务调用失败（Ollama nomic-embed-text）：" + root.getMessage());
        }
    }

    public String model() {
        return model;
    }
}
