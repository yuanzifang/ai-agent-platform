package com.platform.conversation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 提示注入专项检测（启发式）：IN 方向高置信组合拦截、单特征留痕告警；
 * OUT 方向检测疑似系统提示泄露并告警。这不是语义级安全模型，不能替代第三方内容安全/渗透测试。
 */
@Component
public class PromptInjectionModerationProvider implements ContentModerationProvider {

    private static final Pattern IGNORE = Pattern.compile(
            "ignore\\s+(all\\s+)?(previous|above|prior)\\s+instructions"
                    + "|disregard\\s+(all\\s+)?(previous|above|prior)"
                    + "|forget\\s+(all\\s+)?(previous|above|prior)"
                    + "|忽略(之前|以上|所有|前面)(的)?(所有)?(指令|规则|提示)"
                    + "|无视(之前|以上|所有|前面)(的)?(指令|规则|提示)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_LEAK = Pattern.compile(
            "(reveal|show|print|output|repeat|leak).{0,30}(system|developer|hidden)\\s*(prompt|message|instruction)"
                    + "|(system|developer|hidden)\\s*(prompt|message|instruction).{0,30}(reveal|show|print|output)"
                    + "|(输出|显示|打印|泄露|告诉我).{0,30}(系统|开发者|隐藏)(提示词|消息|指令)"
                    + "|(系统|开发者|隐藏)(提示词|消息|指令).{0,30}(输出|显示|打印|泄露)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_LEAK = Pattern.compile(
            "(here('s| is)|this is|my)\\s+(the\\s+|my\\s+)?(system|developer|hidden)\\s*(prompt|message|instruction)"
                    + "|(system|developer|hidden)\\s*(prompt|message|instruction)\\s*(is|:|：)"
                    + "|(这是|我的)?(系统|开发者|隐藏)(提示词|消息|指令)(是|为|：|:)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFETY_BYPASS = Pattern.compile(
            "bypass.{0,20}(safety|policy|guardrail|filter)"
                    + "|ignore.{0,20}(safety|policy|guardrail)"
                    + "|绕过.{0,20}(安全|策略|审核|限制)"
                    + "|无视.{0,20}(安全|策略|审核|限制)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_HIJACK = Pattern.compile(
            "you\\s+are\\s+now\\s+(a|an|the)?\\s*|act\\s+as\\s+.{0,30}(dan|jailbreak)"
                    + "|do\\s+anything\\s+now|jailbreak|越狱|从现在开始你(是|扮演)",
            Pattern.CASE_INSENSITIVE);

    private final boolean enabled;
    private final int blockScore;

    public PromptInjectionModerationProvider(
            @Value("${moderation.prompt-injection.enabled:true}") boolean enabled,
            @Value("${moderation.prompt-injection.block-score:2}") int blockScore) {
        this.enabled = enabled;
        this.blockScore = Math.max(1, blockScore);
    }

    @Override
    public String name() {
        return "prompt-injection";
    }

    @Override
    public List<ModerationService.Hit> check(String direction, String content) {
        if (!enabled || content == null || content.isBlank()) return List.of();
        boolean inbound = "IN".equalsIgnoreCase(direction);
        boolean outbound = "OUT".equalsIgnoreCase(direction);
        if (!inbound && !outbound) return List.of();
        String text = content.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        int score = 0;
        if (inbound) {
            if (IGNORE.matcher(text).find()) { matched.add("ignore-instructions"); score++; }
            if (PROMPT_LEAK.matcher(text).find()) { matched.add("prompt-leak"); score++; }
            if (SAFETY_BYPASS.matcher(text).find()) { matched.add("safety-bypass"); score += 2; }
            if (ROLE_HIJACK.matcher(text).find()) { matched.add("role-hijack"); score++; }
        } else if (PROMPT_LEAK.matcher(text).find() || OUTPUT_LEAK.matcher(text).find()) {
            // 模型输出疑似泄露系统提示：只告警，避免误伤正常引用
            matched.add("output-prompt-leak");
            score = 1;
        }
        if (matched.isEmpty()) return List.of();
        String action = inbound && score >= blockScore ? "BLOCK" : "WARN";
        String match = matched.get(0);
        String evidence = content.length() > 120 ? content.substring(0, 120) + "…" : content;
        return List.of(new ModerationService.Hit("prompt-injection:" + match, action, evidence,
                action.equals("BLOCK") ? "检测到高置信提示注入，已拦截" : "检测到疑似提示注入，已记录"));
    }
}
