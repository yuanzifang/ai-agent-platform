package com.platform.capability.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 单轮工具调用上限：0 表示不限制。用于防止模型无限工具循环/刷下游配额。 */
@Component
public class ToolCallLimitHook implements AgentHook {

    @Value("${agent.tool.max-calls-per-turn:0}")
    private int maxToolCalls;

    @Override
    public String name() {
        return "tool-call-limit";
    }

    @Override
    public Decision beforeTool(ToolContext context) {
        if (maxToolCalls <= 0) return Decision.allow();
        int used = context.collector() == null ? 0 : context.collector().events().size();
        if (used >= maxToolCalls) {
            return Decision.deny("TOOL_CALL_LIMIT",
                    "本轮工具调用次数已达上限（" + maxToolCalls + "），请基于已有结果回答或询问用户");
        }
        return Decision.allow();
    }
}
