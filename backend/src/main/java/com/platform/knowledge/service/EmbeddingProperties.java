package com.platform.knowledge.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 可选的 embedding 模型及维度；知识库创建时选择，向量表按维度隔离。 */
@Data
@Component
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    private String provider = "ollama";
    private String endpoint = "http://localhost:11434";
    private String model = "nomic-embed-text";
    private int dimensions = 768;
    /** 单次 embedding HTTP 请求的最大文本数。 */
    private int batchSize = 32;
    /** modelName -> dimensions；未配置时只有默认模型。 */
    private Map<String, Integer> available = new LinkedHashMap<>();

    public Map<String, Integer> models() {
        Map<String, Integer> result = new LinkedHashMap<>(available);
        if (result.isEmpty() || !result.containsKey(model)) {
            result.put(model, dimensions);
        }
        return result;
    }

    public int dimensionOf(String modelName) {
        if (modelName == null || modelName.isBlank()) return dimensions;
        Integer dim = models().get(modelName);
        if (dim == null) {
            throw new IllegalArgumentException("未配置的 embedding 模型：" + modelName);
        }
        return dim;
    }

    public boolean supports(String modelName) {
        return modelName != null && models().containsKey(modelName);
    }
}
