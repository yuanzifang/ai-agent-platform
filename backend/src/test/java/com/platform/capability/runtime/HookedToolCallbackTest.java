package com.platform.capability.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HookedToolCallbackTest {

    @Test
    void deniesCallAfterToolLimitWithoutInvokingDelegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("echo").description("echo").inputSchema("{}").build());
        when(delegate.call(anyString())).thenReturn("ok");

        ToolCallLimitHook limitHook = new ToolCallLimitHook();
        ReflectionTestUtils.setField(limitHook, "maxToolCalls", 1);
        AgentHookRegistry hooks = new AgentHookRegistry(List.of(limitHook));
        ToolCallCollector collector = new ToolCallCollector();
        HookedToolCallback callback = new HookedToolCallback(delegate, hooks, collector,
                "echo", "Echo", "API");

        assertEquals("ok", callback.call("{}"));
        collector.add(new ToolCallEvent("echo", "Echo", "API", null, null, "SUCCESS", 1,
                "{}", "ok", 0, 0, System.currentTimeMillis(), System.currentTimeMillis()));

        String denied = callback.call("{}");
        assertTrue(denied.contains("TOOL_CALL_LIMIT"), denied);
        verify(delegate, times(1)).call(anyString());
    }
}
