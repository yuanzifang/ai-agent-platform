package com.platform.conversation.service;

import com.platform.model.service.ChatModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/** 智能模型路由：规则命中优先 + LLM 分类器 fail-open（分类失败回原模型）。 */
class ModelRoutingServiceTest {

    private ModelRoutingService service() {
        var router = mock(ChatModelRouter.class);
        var svc = new ModelRoutingService(router, new com.fasterxml.jackson.databind.ObjectMapper());
        return svc;
    }

    private Map<String, Object> modelConfig(boolean routingEnabled, Map<String, Object> routingOverrides) {
        var routing = new java.util.HashMap<String, Object>();
        routing.put("enabled", routingEnabled);
        if (routingOverrides != null) routing.putAll(routingOverrides);
        var config = new java.util.HashMap<String, Object>();
        config.put("modelName", "deepseek-chat");
        config.put("routing", routing);
        return config;
    }

    @Test
    void ruleKeywordMatchRoutesToSpecializedModel() {
        var svc = service();
        var config = modelConfig(true, Map.of(
                "rules", List.of(Map.of("match", List.of("代码", "编程", "debug"), "model", "deepseek-coder"))
        ));
        var decision = svc.resolve(config, "帮我写一个Python函数来debug这个错误");
        assertEquals("deepseek-coder", decision.model());
        assertEquals("rule:keyword", decision.reason());
    }

    @Test
    void noRuleMatchReturnsNull() {
        var svc = service();
        var config = modelConfig(true, Map.of(
                "rules", List.of(Map.of("match", List.of("代码", "编程"), "model", "deepseek-coder"))
        ));
        assertNull(svc.resolve(config, "你好，今天天气怎么样"));
    }

    @Test
    void routingDisabledReturnsNull() {
        var svc = service();
        var config = modelConfig(false, Map.of(
                "rules", List.of(Map.of("match", List.of("代码"), "model", "deepseek-coder"))
        ));
        assertNull(svc.resolve(config, "帮我debug代码"));
    }

    @Test
    void noRoutingConfigReturnsNull() {
        var svc = service();
        var config = new java.util.HashMap<String, Object>();
        config.put("modelName", "deepseek-chat");
        assertNull(svc.resolve(config, "帮我写代码"));
    }

    @Test
    void blankMessageReturnsNull() {
        var svc = service();
        assertNull(svc.resolve(modelConfig(true, null), ""));
    }

    @Test
    void emptyRulesFallThrough() {
        var svc = service();
        var config = modelConfig(true, Map.of());   // 无 rules 无 classifierModel
        assertNull(svc.resolve(config, "随便聊聊"));
    }
}
