package com.platform.capability.runtime;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 在工具回调外层执行统一 Hook；拒绝时不进入真实工具执行。 */
public class HookedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentHookRegistry hooks;
    private final ToolCallCollector collector;
    private final String toolKey;
    private final String toolName;
    private final String toolType;
    private final java.util.Map<String, Object> capabilities;

    public HookedToolCallback(ToolCallback delegate, AgentHookRegistry hooks, ToolCallCollector collector,
                              String toolKey, String toolName, String toolType,
                              java.util.Map<String, Object> capabilities) {
        this.delegate = delegate;
        this.hooks = hooks;
        this.collector = collector;
        this.toolKey = toolKey;
        this.toolName = toolName;
        this.toolType = toolType;
        this.capabilities = capabilities == null ? java.util.Map.of() : capabilities;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        AgentHook.ToolContext context = new AgentHook.ToolContext(toolKey, toolName, toolType,
                toolInput, collector, capabilities);
        AgentHook.Decision decision = hooks.beforeTool(context);
        if (!decision.allowed()) {
            String output = "{\"ok\":false,\"error\":\"" + decision.code() + "\",\"retryable\":"
                    + decision.retryable() + ",\"message\":\"" + escape(decision.message()) + "\"}";
            if (collector != null) {
                long now = System.currentTimeMillis();
                collector.add(new ToolCallEvent(toolKey, toolName, toolType, null, null, "DENIED",
                        0, toolInput == null ? "" : toolInput, output, 0, 0, now, now));
            }
            return output;
        }
        String status = "SUCCESS";
        String output;
        try {
            output = delegate.call(toolInput);
        } catch (RuntimeException e) {
            status = "ERROR";
            hooks.afterTool(context, e.getMessage(), status);
            throw e;
        }
        return hooks.afterTool(context, output, status);
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
