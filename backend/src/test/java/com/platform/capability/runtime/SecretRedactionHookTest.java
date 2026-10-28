package com.platform.capability.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactionHookTest {

    private final SecretRedactionHook hook = new SecretRedactionHook();

    @Test
    void redactsCommonSecretsButKeepsNormalOutput() {
        AgentHook.ToolContext context = new AgentHook.ToolContext(
                "t", "T", "API", "{}", new ToolCallCollector(), java.util.Map.of());
        String output = "key=sk-1234567890abcdefghijklmn Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456";

        String redacted = hook.afterTool(context, output, "SUCCESS");

        assertFalse(redacted.contains("sk-1234567890abcdefghijklmn"));
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz123456"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertEquals("normal result", hook.afterTool(context, "normal result", "SUCCESS"));
    }

    @Test
    void canBeDisabled() {
        ReflectionTestUtils.setField(hook, "enabled", false);
        AgentHook.ToolContext context = new AgentHook.ToolContext(
                "t", "T", "API", "{}", new ToolCallCollector(), java.util.Map.of());
        String output = "sk-1234567890abcdefghijklmn";
        assertEquals(output, hook.afterTool(context, output, "SUCCESS"));
    }
}
