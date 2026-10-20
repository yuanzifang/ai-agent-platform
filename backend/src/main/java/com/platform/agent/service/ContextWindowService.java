package com.platform.agent.service;

import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.model.entity.ModelEntity;
import com.platform.model.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口管理（需求 3.1.3）：按模型 context_window（创建/同步模型时配置）估算 Token；
 * 超限时截断系统提示 + 对较早历史做 LLM 摘要压缩，仍超限则丢弃最旧消息。
 * 估算保守（CJK≈1 token/字，其他≈4 字符/token），预留下游输出 Token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextWindowService {

    private final ModelMapper modelMapper;
    private final com.platform.model.service.ChatModelRouter chatModelRouter;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;

    @Value("${llm.context.default-window:32768}")
    private int defaultWindow;

    @Value("${llm.context.reserve-tokens:2048}")
    private int reserveTokens;

    @Value("${llm.context.compress-enabled:true}")
    private boolean compressEnabled;

    public record Fitted(String systemPrompt, List<Message> history,
                         boolean compressed, int droppedMessages, int estimatedTokens) {
    }

    /** 完整对话裁剪：系统提示/用户输入/历史消息适配模型上下文。 */
    public Fitted fit(String modelName, String systemPrompt, String userMessage, List<Message> history) {
        int limit = windowOf(modelName);
        int reserve = Math.min(Math.max(reserveTokens, 256), Math.max(limit / 4, 256));
        int budget = Math.max(limit - reserve, 512);

        int userTokens = estimate(userMessage);
        if (userTokens > budget * 4 / 5) {
            throw new BusinessException(400, "输入内容超出模型上下文窗口（约 " + userTokens
                    + " tokens / 上限 " + budget + "），请缩短消息或附件");
        }
        String prompt = systemPrompt == null ? "" : systemPrompt;
        int promptTokens = estimate(prompt);
        int availableForPrompt = Math.max(budget - userTokens - 32, 100);
        boolean truncated = false;
        if (promptTokens > availableForPrompt) {
            prompt = truncateChars(prompt, Math.max(availableForPrompt, 50));
            promptTokens = estimate(prompt);
            truncated = true;
            log.info("系统提示超出上下文，已截断 model={} 原 {} tokens → {} tokens",
                    modelName, estimate(systemPrompt), promptTokens);
        }

        // 从最新历史向前保留，超出的进入待压缩列表
        List<Message> kept = new ArrayList<>();
        List<Message> dropped = new ArrayList<>();
        int used = promptTokens + userTokens;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            int t = estimate(m.getText()) + 4;
            if (used + t <= budget) {
                kept.add(0, m);
                used += t;
            } else {
                dropped.add(0, m);
            }
        }

        boolean compressed = truncated;
        int droppedCount = dropped.size();
        if (!dropped.isEmpty()) {
            compressed = true;
            String summary = compressEnabled ? summarize(modelName, dropped, Math.max(budget / 4, 128)) : null;
            if (summary == null || summary.isBlank()) {
                summary = extractive(dropped, Math.max(budget / 4, 128));
            }
            int summaryTokens = estimate(summary) + 8;
            if (used + summaryTokens > budget) {
                summary = truncateChars(summary, Math.max(budget - used - 8, 50));
                summaryTokens = estimate(summary);
            }
            if (!summary.isBlank()) {
                kept.add(0, new SystemMessage("[较早对话的压缩摘要]\n" + summary));
                used += summaryTokens;
            }
            log.info("上下文压缩 model={} 丢弃/摘要 {} 条历史，剩余 {} 条，约 {} tokens（上限 {}，预留 {}）",
                    modelName, droppedCount, kept.size(), used, limit, reserve);
        }
        return new Fitted(prompt, kept, compressed, droppedCount, used);
    }

    /** 无历史场景（调试/子 Agent）：仅裁剪系统提示，用户输入超限直接报错。 */
    public String fitPrompt(String modelName, String systemPrompt, String userMessage) {
        int limit = windowOf(modelName);
        int reserve = Math.min(Math.max(reserveTokens, 256), Math.max(limit / 4, 256));
        int budget = Math.max(limit - reserve, 512);
        int userTokens = estimate(userMessage);
        if (userTokens > budget * 4 / 5) {
            throw new BusinessException(400, "输入内容超出模型上下文窗口（约 " + userTokens + " tokens）");
        }
        String prompt = systemPrompt == null ? "" : systemPrompt;
        int allowed = Math.max(budget - userTokens - 32, 50);
        if (estimate(prompt) > allowed) {
            int original = estimate(prompt);
            prompt = truncateChars(prompt, allowed);
            log.info("系统提示超出上下文，已截断 model={} 原 {} tokens → {} tokens",
                    modelName, original, estimate(prompt));
        }
        return prompt;
    }

    public int windowOf(String modelName) {
        try {
            ModelEntity m = modelMapper.findForPricing(modelName, TenantContext.getTenantId());
            if (m != null && m.getContextWindow() != null && m.getContextWindow() > 0) {
                return m.getContextWindow();
            }
        } catch (Exception e) {     log.debug("模型上下文窗口读取失败，使用默认值：{}", e.getMessage()); }
        return Math.max(defaultWindow, 4096);
    }

    /** 粗略 Token 估算：CJK 1 token/字，其他 4 字符/token，附加消息开销。 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3040 && c <= 0x30FF
                    || c >= 0xAC00 && c <= 0xD7AF) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return cjk + (int) Math.ceil(other / 4.0) + 4;
    }

    private String summarize(String modelName, List<Message> dropped, int maxTokens) {
        try {
            StringBuilder text = new StringBuilder();
            for (Message m : dropped) {
                String role = m instanceof UserMessage ? "用户" : (m instanceof AssistantMessage ? "助手" : "系统");
                text.append(role).append("：").append(m.getText()).append('\n');
            }
            String source = truncateChars(text.toString(), Math.max(maxTokens * 3, 400));
            OpenAiChatOptions options = OpenAiChatOptions.builder().model(modelName).temperature(0.2).build();
            var response = ChatClient.create(chatModelRouter.resolve(modelName)).prompt().options(options)
                    .system("你是对话压缩器。把以下历史对话压缩成简洁要点，保留事实、数字、结论和未完成事项；"
                            + "不超过 " + Math.max(maxTokens, 80) + " 字，不要添加新信息。")
                    .user(source)
                    .call().chatResponse();
            recordCompressionUsage(modelName, response);
            String summary = response.getResult() == null ? null : response.getResult().getOutput().getText();
            return summary == null ? null : summary.trim();
        } catch (Exception e) {
            log.warn("历史摘要生成失败（回落截断）model={}：{}", modelName, e.getMessage());
            return null;
        }
    }

    /** 摘要调用也计入用量（source=intelligent），避免压缩成本不可见。 */
    private void recordCompressionUsage(String modelName, org.springframework.ai.chat.model.ChatResponse response) {
        try {
            long in = 0, out = 0;
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                in = response.getMetadata().getUsage().getPromptTokens();
                out = response.getMetadata().getUsage().getCompletionTokens();
            }
            BillingUsage u = new BillingUsage();
            u.setTenantId(TenantContext.getTenantId());
            u.setSource("intelligent");
            u.setModelName(modelName);
            u.setInputTokens(in);
            u.setOutputTokens(out);
            u.setCost(pricingService.cost(TenantContext.getTenantId(), modelName, in, out));
            u.setStatDate(java.time.LocalDate.now());
            u.setCreatedAt(java.time.LocalDateTime.now());
            billingMapper.insert(u);
        } catch (Exception e) {
            log.debug("上下文压缩用量记录失败：{}", e.getMessage());
        }
    }

    private String extractive(List<Message> dropped, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        for (Message m : dropped) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m instanceof UserMessage ? "用户：" : "助手：").append(m.getText());
        }
        return truncateChars(sb.toString(), maxTokens);
    }

    /** 按估算 Token 截断（CJK 按字，其他按 4 字符/token 的保守比例）。 */
    private String truncateChars(String text, int tokenLimit) {
        if (text == null) return "";
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3040 && c <= 0x30FF
                    || c >= 0xAC00 && c <= 0xD7AF) cjk++;
        }
        // 估算每 token 平均字符数，保守按 1 字符/token 上限截断
        int maxChars = Math.max(tokenLimit, 32);
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars);
    }
}
