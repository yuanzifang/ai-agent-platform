package com.platform.capability.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工具/Agent Hook 链；单个 Hook 异常记录日志后放行，避免影响主链路。 */
@Slf4j
@Component
public class AgentHookRegistry {

    private final List<AgentHook> hooks;

    public AgentHookRegistry(List<AgentHook> hooks) {
        this.hooks = hooks == null ? List.of() : hooks;
    }

    public AgentHook.Decision beforeTool(AgentHook.ToolContext context) {
        for (AgentHook hook : hooks) {
            try {
                AgentHook.Decision decision = hook.beforeTool(context);
                if (decision != null && !decision.allowed()) return decision;
            } catch (Exception e) {
                log.warn("AgentHook[{}] beforeTool 异常，放行：{}", hook.name(), e.getMessage());
            }
        }
        return AgentHook.Decision.allow();
    }

    public String afterTool(AgentHook.ToolContext context, String output, String status) {
        String current = output;
        for (AgentHook hook : hooks) {
            try {
                String next = hook.afterTool(context, current, status);
                if (next != null) current = next;
            } catch (Exception e) {
                log.warn("AgentHook[{}] afterTool 异常，保留当前输出：{}", hook.name(), e.getMessage());
            }
        }
        return current;
    }
}
