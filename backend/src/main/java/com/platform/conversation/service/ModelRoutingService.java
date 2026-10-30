package com.platform.conversation.service;

import com.platform.model.service.ChatModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能模型路由：按用户问题的复杂度/意图，在同一 Agent 的多个模型间自动分流。
 *
 * <p>配置于 Agent modelConfig.routing（JSON）：{@code
 * { enabled: true, classifierModel: "", rules: [{match:["代码","编程"], model:"deepseek-coder"}],
 *   simpleModel: "deepseek-flash", complexModel: "deepseek-reasoner" } }</p>
 *
 * <p>优先级：① 规则关键词命中 → 直接切（零额外延迟）；② 无规则命中且配了 classifierModel
 * → 用轻量模型做一次复杂度二分类（结果缓存 30 min TTL，同问不重复分类）；③ 都未命中 → 原模型。
 * fail-open 语义：分类器异常/超时/返回不可解析 → 保持原模型，绝不阻断对话。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRoutingService {

    private static final int CACHE_MAX = 500;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final long CLASSIFY_TIMEOUT_MS = 1_500L;

    private final ChatModelRouter chatModelRouter;
    private final ObjectMapper objectMapper;

    /** lang → (key → {value, loadedAt})；TTL 30 min + 写入即失效。 */
    private final Map<String, CachedClassify> classifyCache = new ConcurrentHashMap<>();

    private record CachedClassify(String complexity, long loadedAt) {
        boolean fresh() {
            return System.currentTimeMillis() - loadedAt < CACHE_TTL_MS;
        }
    }

    public record RoutingDecision(String model, String reason) {
    }

    /** 解析目标模型：返回 null 表示保持原模型。 */
    public RoutingDecision resolve(Map<String, Object> modelConfig, String userMessage) {
        if (modelConfig == null || userMessage == null || userMessage.isBlank()) return null;
        Object routingObj = modelConfig.get("routing");
        if (!(routingObj instanceof Map<?, ?> rawRouting)) return null;
        Map<String, Object> routing = castMap(rawRouting);
        if (!Boolean.TRUE.equals(routing.get("enabled"))) return null;

        // ① 规则关键词：零延迟
        Object rulesObj = routing.get("rules");
        if (rulesObj instanceof List<?> rules) {
            String lower = userMessage.toLowerCase(Locale.ROOT);
            for (Object ruleObj : rules) {
                if (!(ruleObj instanceof Map<?, ?> rawRule)) continue;
                Map<String, Object> rule = castMap(rawRule);
                if (!(rule.get("match") instanceof List<?> keywords)) continue;
                for (Object kw : keywords) {
                    if (kw == null) continue;
                    if (lower.contains(String.valueOf(kw).toLowerCase(Locale.ROOT))) {
                        String model = str(rule.get("model"));
                        if (model != null && !model.isBlank()) {
                            return new RoutingDecision(model, "rule:" + str(rule.getOrDefault("intent", "keyword")));
                        }
                        break;
                    }
                }
            }
        }

        // ② LLM 复杂度分类器（带 1.5s 超时：分类器响应慢时 fail-open 不拖首字节）
        String classifierModel = str(routing.get("classifierModel"));
        String simpleModel = str(routing.get("simpleModel"));
        String complexModel = str(routing.get("complexModel"));
        if (classifierModel == null || classifierModel.isBlank()) return null;
        if (simpleModel == null && complexModel == null) return null;

        CachedClassify cached = classifyCache.get(userMessage);
        if (cached == null || !cached.fresh()) {
            String complexity = classifyWithTimeout(classifierModel, userMessage);
            cached = new CachedClassify(complexity, System.currentTimeMillis());
            if (classifyCache.size() >= CACHE_MAX) classifyCache.clear();
            classifyCache.put(userMessage, cached);
        }
        String complexity = cached.complexity();
        return switch (complexity) {
            case "simple" -> simpleModel.isBlank() ? null
                    : new RoutingDecision(simpleModel, "complexity:simple");
            case "complex" -> complexModel.isBlank() ? null
                    : new RoutingDecision(complexModel, "complexity:complex");
            default -> null;
        };
    }

    /** 复杂度分类（包级可见供单测注入 mock 结果）。 */
    String classifyComplexity(String classifierModel, String userMessage) {
        try {
            String prompt = "判断以下用户消息的复杂度。只回复 JSON：{\"complexity\":\"simple\"} 或 {\"complexity\":\"complex\"}。"
                    + "simple=打招呼/闲聊/单轮事实查询；complex=多步推理/代码/文档分析/创作。不要回复其他内容。\n用户消息：" + userMessage;
            var chatModel = chatModelRouter.resolve(classifierModel);
            String resp = ChatClient.create(chatModel).prompt().user(prompt)
                    .options(ChatOptions.builder()
                            .model(classifierModel).maxTokens(20).temperature(0.0).build())
                    .call().entity(String.class);
            if (resp == null || resp.isBlank()) return "";
            String cleaned = resp.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (cleaned.contains("complex") && !cleaned.contains("simple")) return "complex";
            if (cleaned.contains("simple")) return "simple";
            return "";
        } catch (Exception e) {
            log.debug("模型路由分类失败（回原模型）：{}", e.getMessage());
            return "";
        }
    }

    /** 分类 + 1.5s 超时守卫：分类器响应慢时 fail-open（不拖首字节）。 */
    private String classifyWithTimeout(String classifierModel, String userMessage) {
        var future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> classifyComplexity(classifierModel, userMessage));
        try {
            return future.get(CLASSIFY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.debug("模型路由分类超时（{}ms）", CLASSIFY_TIMEOUT_MS);
            return "";
        } catch (Exception e) {
            log.debug("模型路由分类异常：{}", e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
