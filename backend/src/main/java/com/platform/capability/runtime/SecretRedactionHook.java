package com.platform.capability.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        if (!enabled || output == null || output.isEmpty()) return output;
        String redacted = output;
        for (Pattern pattern : PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }
}
