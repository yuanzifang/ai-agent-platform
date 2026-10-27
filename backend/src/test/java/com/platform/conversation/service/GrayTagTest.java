package com.platform.conversation.service;

import com.platform.observability.service.TraceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 灰度标记：清洗规则与 Trace tags 附加（X-Gray-Tag 全链路透传，需求 12 步对照第 12 步）。 */
class GrayTagTest {

    @Test
    void sanitizeKeepsSafeCharsOnly() {
        assertEquals("canary-v2.1", ChatStreamService.sanitizeGrayTag(" canary-v2.1 "));
        assertEquals("tenant_A:2026", ChatStreamService.sanitizeGrayTag("tenant_A:2026"));
        assertNull(ChatStreamService.sanitizeGrayTag(null));
        assertNull(ChatStreamService.sanitizeGrayTag("  "));
        assertNull(ChatStreamService.sanitizeGrayTag("中文标记"), "非白名单字符全部剥离后为空");
        assertTrue(ChatStreamService.sanitizeGrayTag("ab<script>12; drop table x--").matches("^[A-Za-z0-9._:-]{1,64}$"),
                "输出必须仅含白名单字符：" + ChatStreamService.sanitizeGrayTag("ab<script>12; drop table x--"));
        String longTag = "x".repeat(100);
        assertEquals(64, ChatStreamService.sanitizeGrayTag(longTag).length(), "超长截断 64");
    }

    @Test
    void applyGrayTagAppendsToExistingTags() {
        TraceService.TraceExtra extra = new TraceService.TraceExtra(
                "internal", "v3", "in", "out", null, List.of("eval:online"));
        TraceService.TraceExtra tagged = ChatStreamService.applyGrayTag(extra, "canary-v2");
        assertEquals(List.of("eval:online", "gray:canary-v2"), tagged.tags());
    }

    @Test
    void blankGrayTagKeepsExtraUntouched() {
        TraceService.TraceExtra extra = TraceService.TraceExtra.internal("v3", "in", "out");
        assertTrue(ChatStreamService.applyGrayTag(extra, null) == extra);
        assertTrue(ChatStreamService.applyGrayTag(extra, "  ") == extra);
    }
}
