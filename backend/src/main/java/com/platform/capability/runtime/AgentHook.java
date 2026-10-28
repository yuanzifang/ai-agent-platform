package com.platform.capability.runtime;

/** Agent 执行生命周期扩展点：工具调用前后、模型/Agent 前后可挂策略。 */
public interface AgentHook {

    record ToolContext(String toolKey, String toolName, String toolType, String input,
                       ToolCallCollector collector) {
    }

    record Decision(boolean allowed, boolean retryable, String code, String message) {
        public static Decision allow() {
            return new Decision(true, false, null, null);
        }

        public static Decision deny(String code, String message) {
            return new Decision(false, false, code, message);
        }
    }

    default String name() {
        return getClass().getSimpleName();
    }

    default Decision beforeTool(ToolContext context) {
        return Decision.allow();
    }

    default void afterTool(ToolContext context, String output, String status) {
    }
}
