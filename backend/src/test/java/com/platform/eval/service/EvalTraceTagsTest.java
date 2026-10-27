package com.platform.eval.service;

import com.platform.eval.entity.EvalTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 评测轨迹 tags：类型 + 灰度标记（gray:<tag>），供 A/B 对比分组。 */
class EvalTraceTagsTest {

    private EvalTask task(String evalType, String grayTag) {
        EvalTask t = new EvalTask();
        t.setEvalType(evalType);
        t.setGrayTag(grayTag);
        return t;
    }

    @Test
    void includesTypeAndGrayTag() {
        assertEquals(List.of("eval", "pre_release", "gray:canary-v2"),
                EvalService.evalTraceTags(task("PRE_RELEASE", "canary-v2"), "pre_release"));
    }

    @Test
    void noGrayTagOmitsEntry() {
        List<String> tags = EvalService.evalTraceTags(task("ONCE", null), "once");
        assertEquals(List.of("eval", "once"), tags);
        assertFalse(tags.stream().anyMatch(tag -> tag.startsWith("gray:")));
    }
}
