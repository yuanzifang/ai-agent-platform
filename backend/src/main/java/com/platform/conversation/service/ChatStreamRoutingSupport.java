package com.platform.conversation.service;

import com.platform.capability.runtime.TraceStepCollector;
import com.platform.common.context.GrayTagContext;

import java.util.Map;

/** ChatStream 的灰度/智能路由纯函数，独立于 SSE 生命周期。 */
final class ChatStreamRoutingSupport {

    private ChatStreamRoutingSupport() {
    }

    /** 灰度标记命中 grayTag 时切换 grayModel；未命中/未配置返回原模型。 */
    static String applyGrayModel(Map<String, Object> modelConfig, String currentModel) {
        String tag = GrayTagContext.get();
        if (tag == null || tag.isBlank()) return currentModel;
        Object grayTag = modelConfig.get("grayTag");
        Object grayModel = modelConfig.get("grayModel");
        if (grayTag == null || grayModel == null) return currentModel;
        return tag.trim().equalsIgnoreCase(String.valueOf(grayTag).trim())
                ? String.valueOf(grayModel) : currentModel;
    }

    /** 灰度模型之后执行 Agent 级智能路由；灰度命中时优先灰度模型。 */
    static String resolveRoutedModel(ModelRoutingService modelRoutingService,
                                     Map<String, Object> modelConfig, String userMessage,
                                     String baseModel, TraceStepCollector steps) {
        String current = applyGrayModel(modelConfig, baseModel);
        if (!current.equals(baseModel)) return current;

        long started = System.currentTimeMillis();
        ModelRoutingService.RoutingDecision decision = modelRoutingService.resolve(modelConfig, userMessage);
        if (decision == null || decision.model() == null || decision.model().isBlank()) {
            return current;
        }
        String routed = applyGrayModel(modelConfig, decision.model());
        if (steps != null) {
            steps.add("model", "smart-routing", "SUCCESS", started, System.currentTimeMillis(),
                    Map.of("from", current, "to", routed, "reason", decision.reason()));
        }
        return routed;
    }
}
