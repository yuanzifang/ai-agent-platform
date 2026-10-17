package com.platform.agent.service;

import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.service.EmbeddingService;
import com.platform.knowledge.service.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话执行（架构 5.1）：ChatClient 不做全局单例，按请求构建；调试链路不写会话与正式 Trace，
 * Token 计入 billing_usage（source=debug，需求文档 3.1.3(10)）。
 * RAG（架构 6.6）：config.knowledgeBases 命中分段注入 system 上下文，并返回 citations。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;                     // 自动装配的 OpenAI 兼容实现（指向 DeepSeek）
    private final BillingUsageMapper billingMapper;
    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;

    @SuppressWarnings("unchecked")
    public Map<String, Object> debugChat(Map<String, Object> config, String message) {
        String systemPrompt = config == null || config.get("systemPrompt") == null
                ? null : String.valueOf(config.get("systemPrompt"));
        Map<String, Object> modelConfig = config == null || config.get("modelConfig") == null
                ? Map.of() : asMap(config.get("modelConfig"));

        // RAG：检索绑定的知识库，命中分段注入（需求文档 3.1.3(7)）
        List<Map<String, Object>> citations = new ArrayList<>();
        if (config != null && config.get("knowledgeBases") instanceof List<?> kbs) {
            systemPrompt = augmentWithKnowledge(systemPrompt, message, kbs, citations);
        }

        String modelName = String.valueOf(modelConfig.getOrDefault("modelName", "deepseek-flash"));
        Double temperature = toDouble(modelConfig.get("temperature"));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        if (temperature != null) options.temperature(temperature);

        var spec = ChatClient.create(chatModel).prompt().options(options.build());
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);

        long start = System.currentTimeMillis();
        var chatResponse = spec.user(message).call().chatResponse();
        String answer = chatResponse.getResult().getOutput().getText();
        Usage usage = chatResponse.getMetadata().getUsage();

        long inputTokens = usage == null ? 0 : usage.getPromptTokens();
        long outputTokens = usage == null ? 0 : usage.getCompletionTokens();

        recordUsage(null, "debug", modelName, inputTokens, outputTokens);
        log.info("debug chat: model={} in={} out={} latency={}ms citations={}",
                modelName, inputTokens, outputTokens, System.currentTimeMillis() - start, citations.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer == null ? "" : answer);
        result.put("latencyMs", System.currentTimeMillis() - start);
        result.put("usage", Map.of("inputTokens", inputTokens, "outputTokens", outputTokens));
        result.put("citations", citations);
        return result;
    }

    /** 知识库检索并拼接参考资料（topK 默认 5、阈值默认 0.5，需求文档 3.1.3(7)）。 */
    @SuppressWarnings("unchecked")
    private String augmentWithKnowledge(String systemPrompt, String message, List<?> kbs,
                                        List<Map<String, Object>> citations) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return systemPrompt;
        StringBuilder context = new StringBuilder();
        for (Object o : kbs) {
            Map<String, Object> binding = asMap(o);
            Long kbId = toLong(binding.get("knowledgeBaseId"));
            if (kbId == null || Boolean.FALSE.equals(binding.get("enabled"))) continue;
            Map<String, Object> rc = binding.get("retrievalConfig") == null
                    ? Map.of() : asMap(binding.get("retrievalConfig"));
            int topK = toInt(rc.get("topK"), 5);
            double threshold = toDoubleOrDefault(rc.get("scoreThreshold"), 0.5);

            float[] queryVector = embeddingService.embedOne(message);
            List<Map<String, Object>> hits = vectorRepository.search(tenantId, queryVector, kbId, topK, threshold);
            for (Map<String, Object> hit : hits) {
                context.append("\n---\n").append(hit.get("content"));
                Map<String, Object> citation = new LinkedHashMap<>();
                citation.put("content", hit.get("content"));
                citation.put("score", hit.get("score"));
                citation.put("metadata", hit.get("metadata"));
                citations.add(citation);
            }
        }
        if (context.isEmpty()) return systemPrompt;
        String rag = "以下是知识库检索到的参考资料，回答时优先依据这些内容：\n" + context;
        return systemPrompt == null || systemPrompt.isBlank()
                ? rag : systemPrompt + "\n\n" + rag;
    }

    private void recordUsage(Long agentId, String source, String modelName,
                             long inputTokens, long outputTokens) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("无租户上下文，跳过用量记录（source={}）", source);
            return;
        }
        BillingUsage u = new BillingUsage();
        u.setTenantId(tenantId);
        u.setAgentId(agentId);
        u.setSource(source);
        u.setModelName(modelName);
        u.setInputTokens(inputTokens);
        u.setOutputTokens(outputTokens);
        u.setCost(null); // 计价权威 billing_rule，M5 接入前记 0/空
        u.setStatDate(LocalDate.now());
        u.setCreatedAt(LocalDateTime.now());
        billingMapper.insert(u);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private double toDoubleOrDefault(Object o, double def) {
        Double d = toDouble(o);
        return d == null ? def : d;
    }

    private int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        }
        return null;
    }
}
