package com.platform.extension.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/**
 * 读取 Agent 级扩展开关。
 *
 * <p>扩展配置格式（保存在 {@code agentCapabilities.extensions}）：</p>
 * <pre>
 * "agentCapabilities": {
 *   "extensions": ["agri-safety"]
 * }
 * </pre>
 *
 * 也兼容逗号分隔字符串和 {@code {"agri-safety": true}} 形式，便于控制台/脚本写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExtensionService {

    private final ObjectMapper objectMapper;

    public boolean enabled(AgentRegistry agent, String extensionId) {
        if (agent == null || extensionId == null || extensionId.isBlank()
                || agent.getAgentCapabilities() == null || agent.getAgentCapabilities().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> capabilities = objectMapper.readValue(
                    agent.getAgentCapabilities(), new TypeReference<>() { });
            return enabled(capabilities.get("extensions"), extensionId);
        } catch (Exception e) {
            log.debug("Agent 扩展配置解析失败 agent={}：{}", agent.getId(), e.getMessage());
            return false;
        }
    }

    private boolean enabled(Object value, String extensionId) {
        if (value instanceof Collection<?> values) {
            return values.stream().anyMatch(item -> extensionId.equals(String.valueOf(item)));
        }
        if (value instanceof Map<?, ?> values) {
            Object item = values.get(extensionId);
            return Boolean.TRUE.equals(item) || "true".equalsIgnoreCase(String.valueOf(item))
                    || "1".equals(String.valueOf(item));
        }
        if (value instanceof String text) {
            for (String item : text.split(",")) {
                if (extensionId.equals(item.trim())) return true;
            }
        }
        return false;
    }
}
