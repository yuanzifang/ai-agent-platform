package com.platform.capability.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOutputLimitHookTest {

    private final ToolOutputLimitHook hook = new ToolOutputLimitHook();
    private final AgentHook.ToolContext context = new AgentHook.ToolContext(
            "t", "T", "API", "{}", new ToolCallCollector(), Map.of("maxToolOutputChars", 5));

    @Test
    void truncatesAccordingToAgentCapability() {
        String output = hook.afterTool(context, "1234567890", "SUCCESS");
        assertTrue(output.startsWith("12345"));
        assertTrue(output.contains("已按 Agent 配置截断"));
    }

    @Test
    void zeroOrMissingLeavesOutputUntouched() {
        AgentHook.ToolContext zero = new AgentHook.ToolContext(
                "t", "T", "API", "{}", new ToolCallCollector(), Map.of("maxToolOutputChars", 0));
        assertEquals("1234567890", hook.afterTool(zero, "1234567890", "SUCCESS"));
    }
}
