package com.platform.conversation.service;

import com.platform.model.service.ChatModelRouter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 智能模型路由：规则命中/分类器 fail-open/缓存 TTL/禁用/优先级。 */
class ModelRoutingServiceTest {

    private ModelRoutingService service() {
        var router = mock(ChatModelRouter.class);
        var svc = new ModelRoutingService(router, new com.fasterxml.jackson.databind.ObjectMapper());
        return svc;
    }

    private Map<String, Object> config(Map<String, Object> routing) {
        var config = new java.util.HashMap<String, Object>();
        config.put("modelName", "deepseek-chat");
        config.put("routing", routing);
        return config;
    }

    @Test
    void ruleKeywordMatch() {
        var svc = service();
        var config = config(Map.of(
                "enabled", true,
                "rules", java.util.List.of(Map.of("match", java.util.List.of("代码", "debug"), "model", "deepseek-coder"))
        ));
        var d = svc.resolve(config, "帮我debug这个错误");
        assertEquals("deepseek-coder", d.model());
        assertTrue(d.reason().startsWith("rule:"));
    }

    @Test
    void routingDisabledReturnsNull() {
        var svc = service();
        var config = config(Map.of("enabled", false));
        assertNull(svc.resolve(config, "hello"));
    }

    @Test
    void noRoutingConfigReturnsNull() {
        var svc = service();
        assertNull(svc.resolve(config(Map.of()), "hello"));
    }

    @Test
    void blankMessageReturnsNull() {
        var svc = service();
        assertNull(svc.resolve(config(Map.of("enabled", true)), ""));
    }

    @Test
    void emptyRulesAndNoClassifierReturnsNull() {
        var svc = service();
        assertNull(svc.resolve(config(Map.of("enabled", true)), "hello"));
    }

    @Test
    void rulesMismatchFallsThrough() {
        var svc = service();
        var config = config(Map.of(
                "enabled", true,
                "rules", java.util.List.of(Map.of("match", java.util.List.of("代码"), "model", "x")),
                "classifierModel", ""
        ));
        assertNull(svc.resolve(config, "你好今天天气"));
    }
}
