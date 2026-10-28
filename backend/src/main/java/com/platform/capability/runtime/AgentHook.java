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

    /** 返回可被后续 Hook 继续处理的输出；默认原样返回。 */
    default String afterTool(ToolContext context, String output, String status) {
        return output;
    }
}
