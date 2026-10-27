package com.platform.capability.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 写工具判定：API 非安全方法、MCP 未声明 readOnlyHint 默认阻断。 */
class ToolPolicyTest {

    @Test
    void apiMethodPolicy() {
        assertFalse(ToolRuntimeService.isWriteOperation("API", "GET", Map.of()));
        assertFalse(ToolRuntimeService.isWriteOperation("API", "head", Map.of()));
        assertTrue(ToolRuntimeService.isWriteOperation("API", "POST", Map.of()));
        assertTrue(ToolRuntimeService.isWriteOperation("API", "DELETE", Map.of()));
    }

    @Test
    void writeArgPolicyChecksAllowlistPatternAndAmount() {
        Map<String, Object> caps = Map.of(
                "writeToolArgRules", Map.of("7", List.of("amount", "currency")),
                "writeToolArgPatterns", Map.of("7", Map.of("currency", "CNY|USD")),
                "writeToolAmountLimits", Map.of("7", Map.of("amount", 1000)));

        assertTrue(ToolRuntimeService.argPolicyAllows(caps, 7L,
                Map.of("amount", 100, "currency", "CNY")));
        assertFalse(ToolRuntimeService.argPolicyAllows(caps, 7L,
                Map.of("amount", 100, "currency", "EUR")));
        assertFalse(ToolRuntimeService.argPolicyAllows(caps, 7L,
                Map.of("amount", 1001, "currency", "CNY")));
        assertFalse(ToolRuntimeService.argPolicyAllows(caps, 7L,
                Map.of("amount", 100, "currency", "CNY", "card", "x")));
    }

    @Test
    void writeArgPolicyInvalidConfigFailsClosed() {
        assertFalse(ToolRuntimeService.argPolicyAllows(
                Map.of("writeToolArgPatterns", Map.of("7", Map.of("name", "["))),
                7L, Map.of("name", "x")));
        assertFalse(ToolRuntimeService.argPolicyAllows(
                Map.of("writeToolAmountLimits", Map.of("7", Map.of("amount", "not-a-number"))),
                7L, Map.of("amount", 1)));
    }

    @Test
    void mcpAnnotationsPolicy() {
        assertFalse(ToolRuntimeService.isWriteOperation("MCP", "POST",
                Map.of("readOnlyHint", true)));
        assertTrue(ToolRuntimeService.isWriteOperation("MCP", "POST",
                Map.of("readOnlyHint", false, "destructiveHint", true)));
        assertTrue(ToolRuntimeService.isWriteOperation("MCP", "POST", Map.of()),
                "未声明 annotations 时保守拦截");
    }
}
