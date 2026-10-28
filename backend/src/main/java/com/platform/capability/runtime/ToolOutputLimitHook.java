package com.platform.capability.runtime;

import org.springframework.stereotype.Component;

/** Agent 级工具输出字符上限；0 表示沿用全局/工具自身限制。 */
@Component
public class ToolOutputLimitHook implements AgentHook {

    @Override
    public String name() {
        return "tool-output-limit";
    }

    @Override
    public String afterTool(ToolContext context, String output, String status) {
        if (output == null || context.capabilities() == null) return output;
        Object configured = context.capabilities().get("maxToolOutputChars");
        int max;
        if (configured instanceof Number number) {
            max = number.intValue();
        } else if (configured != null) {
            try {
                max = Integer.parseInt(String.valueOf(configured));
            } catch (NumberFormatException ignored) {
                return output;
            }
        } else {
            return output;
        }
        if (max <= 0 || output.length() <= max) return output;
        return output.substring(0, max) + "\n…（工具输出已按 Agent 配置截断）";
    }
}
