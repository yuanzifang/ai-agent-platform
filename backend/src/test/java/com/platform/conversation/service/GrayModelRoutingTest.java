package com.platform.conversation.service;

import com.platform.common.context.GrayTagContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 灰度模型路由：标记命中切 grayModel，未命中/未配置保持原模型（X-Gray-Tag 消费端）。 */
class GrayModelRoutingTest {

    private Map<String, Object> config(String grayTag, String grayModel) {
        Map<String, Object> config = new HashMap<>();
        config.put("modelName", "deepseek-chat");
        config.put("fallbackModelName", "deepseek-flash");
        if (grayTag != null) config.put("grayTag", grayTag);
        if (grayModel != null) config.put("grayModel", grayModel);
        return config;
    }

    @AfterEach
    void tearDown() {
        GrayTagContext.clear();
    }

    @Test
    void tagMatchSwitchesToGrayModel() {
        GrayTagContext.set("canary-v2");
        assertEquals("deepseek-v3-new",
                ChatStreamService.applyGrayModel(config("canary-v2", "deepseek-v3-new"), "deepseek-chat"));
    }

    @Test
    void tagMatchCaseInsensitive() {
        GrayTagContext.set("Canary-V2");
        assertEquals("deepseek-v3-new",
                ChatStreamService.applyGrayModel(config("canary-v2", "deepseek-v3-new"), "deepseek-chat"));
    }

    @Test
    void tagMismatchKeepsBaseModel() {
        GrayTagContext.set("other-tag");
        assertEquals("deepseek-chat",
                ChatStreamService.applyGrayModel(config("canary-v2", "deepseek-v3-new"), "deepseek-chat"));
    }

    @Test
    void noTagKeepsBaseModel() {
        assertEquals("deepseek-chat",
                ChatStreamService.applyGrayModel(config("canary-v2", "deepseek-v3-new"), "deepseek-chat"));
    }

    @Test
    void noGrayConfigKeepsBaseModel() {
        GrayTagContext.set("canary-v2");
        assertEquals("deepseek-chat", ChatStreamService.applyGrayModel(config(null, null), "deepseek-chat"));
    }

    @Test
    void grayModelWithoutTagKeepsBaseModel() {
        // 只配了 grayModel 没配 grayTag：永不切换（防止误切全量流量）
        GrayTagContext.set("canary-v2");
        assertEquals("deepseek-chat", ChatStreamService.applyGrayModel(config(null, "deepseek-v3-new"), "deepseek-chat"));
    }
}
