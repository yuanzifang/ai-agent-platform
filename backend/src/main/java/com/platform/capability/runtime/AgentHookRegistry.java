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

    public void afterTool(AgentHook.ToolContext context, String output, String status) {
        for (AgentHook hook : hooks) {
            try {
                hook.afterTool(context, output, status);
            } catch (Exception e) {
                log.warn("AgentHook[{}] afterTool 异常，忽略：{}", hook.name(), e.getMessage());
            }
        }
    }
}
