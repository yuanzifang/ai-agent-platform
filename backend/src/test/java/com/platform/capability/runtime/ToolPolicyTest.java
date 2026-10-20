package com.platform.capability.runtime;

import org.junit.jupiter.api.Test;

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
    void mcpAnnotationsPolicy() {
        assertFalse(ToolRuntimeService.isWriteOperation("MCP", "POST",
                Map.of("readOnlyHint", true)));
        assertTrue(ToolRuntimeService.isWriteOperation("MCP", "POST",
                Map.of("readOnlyHint", false, "destructiveHint", true)));
        assertTrue(ToolRuntimeService.isWriteOperation("MCP", "POST", Map.of()),
                "未声明 annotations 时保守拦截");
    }
}
