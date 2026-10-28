package com.platform.capability.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 工具输出敏感信息兜底脱敏；默认开启，可按配置关闭。 */
@Component
public class SecretRedactionHook implements AgentHook {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("sk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("Bearer\\s+[A-Za-z0-9._-]{20,}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?"
                    + "-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"));

    @Value("${agent.hook.redact-secrets.enabled:true}")
    private boolean enabled = true;

    @Override
    public String name() {
        return "secret-redaction";
    }

    @Override
    public String afterTool(ToolContext context, String output, String status) {
        boolean agentEnabled = enabled;
        Object configured = context.capabilities() == null ? null : context.capabilities().get("redactSecrets");
        if (configured instanceof Boolean value) agentEnabled = value;
        else if (configured != null) agentEnabled = !"false".equalsIgnoreCase(String.valueOf(configured));
        if (!agentEnabled || output == null || output.isEmpty()) return output;
        String redacted = output;
        for (Pattern pattern : PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        Object custom = context.capabilities() == null ? null : context.capabilities().get("redactPatterns");
        if (custom instanceof List<?> values) {
            int count = 0;
            for (Object raw : values) {
                if (count++ >= 20) break;
                String expression = String.valueOf(raw);
                if (expression.length() > 200) continue;
                try {
                    redacted = Pattern.compile(expression).matcher(redacted).replaceAll("[REDACTED]");
                } catch (Exception ignored) {
                    // 非法自定义规则跳过，不影响其他规则
                }
            }
        }
        return redacted;
    }
}
