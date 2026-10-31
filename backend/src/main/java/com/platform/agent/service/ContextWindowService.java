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
import java.util.regex.Pattern;

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

    /** RAG 在系统提示预算中的目标占比；剩余留给系统指令。 */
    @Value("${llm.context.rag-budget-ratio:0.4}")
    private double ragBudgetRatio = 0.4;

    public record Fitted(String systemPrompt, List<Message> history,
                         boolean compressed, int droppedMessages, int estimatedTokens,
                         boolean ragTruncated, int ragKeptFragments, int ragTotalFragments) {
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
        boolean ragTruncated = false;
        int ragKeptFragments = 0;
        int ragTotalFragments = 0;
        if (promptTokens > availableForPrompt) {
            PromptFit promptFit = fitSystemPrompt(prompt, Math.max(availableForPrompt, 50), modelName);
            prompt = promptFit.text();
            promptTokens = estimate(prompt);
            truncated = true;
            ragTruncated = promptFit.ragTruncated();
            ragKeptFragments = promptFit.ragKeptFragments();
            ragTotalFragments = promptFit.ragTotalFragments();
            log.info("系统提示超出上下文，已按 RAG 片段边界截断 model={} 原 {} tokens → {} tokens ragTruncated={} fragments={}/{}",
                    modelName, estimate(systemPrompt), promptTokens, ragTruncated,
                    ragKeptFragments, ragTotalFragments);
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
        return new Fitted(prompt, kept, compressed, droppedCount, used, ragTruncated,
                ragKeptFragments, ragTotalFragments);
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
            PromptFit fitted = fitSystemPrompt(prompt, allowed, modelName);
            prompt = fitted.text();
            log.info("系统提示超出上下文，已按 RAG 片段边界截断 model={} 原 {} tokens → {} tokens ragTruncated={} fragments={}/{}",
                    modelName, original, estimate(prompt), fitted.ragTruncated(),
                    fitted.ragKeptFragments(), fitted.ragTotalFragments());
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
            org.springframework.ai.chat.prompt.ChatOptions options =
                    chatModelRouter.options(modelName, 0.2, java.util.List.of());
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

    record PromptFit(String text, boolean truncated, boolean ragTruncated,
                     int ragKeptFragments, int ragTotalFragments) {
    }

    /**
     * 系统提示裁剪：普通提示仍按字符兜底；包含 RAG 时按完整片段保留，
     * 不切断 <untrusted_retrieval> 边界，并给 RAG 单独预算。
     */
    private PromptFit fitSystemPrompt(String prompt, int budget, String modelName) {
        if (prompt == null || prompt.isEmpty()) return new PromptFit("", false, false, 0, 0);
        int open = prompt.indexOf("<untrusted_retrieval>");
        int close = prompt.lastIndexOf("</untrusted_retrieval>");
        if (open < 0 || close < open) {
            if (open >= 0) {
                // 不完整的不可信边界不能交给模型：去掉孤立标签，保留标签前指令。
                String base = prompt.substring(0, open).trim();
                String note = "\n\n【系统提示：知识库检索结果因边界不完整已省略。】";
                return new PromptFit(truncateChars(base + note, budget), true, true, 0, 0);
            }
            return new PromptFit(truncateChars(prompt, budget), true, false, 0, 0);
        }

        String header = "<untrusted_retrieval>";
        String trailer = "</untrusted_retrieval>";
        // RAG 后面可能还有长期记忆等可信系统内容（ChatStreamService 的拼接顺序），必须保留。
        String suffix = prompt.substring(close + trailer.length()).trim();
        String base = prompt.substring(0, open).trim();
        if (!suffix.isBlank()) base = (base + "\n\n" + suffix).trim();
        String block = prompt.substring(open + header.length(), close).trim();
        List<String> fragments = new ArrayList<>();
        String[] rawParts = block.contains(ChatService.RAG_FRAGMENT_SEPARATOR)
                ? block.split(Pattern.quote(ChatService.RAG_FRAGMENT_SEPARATOR))
                : block.split("\n---\n");
        for (String part : rawParts) {
            if (!part.isBlank()) fragments.add(part.trim());
        }
        int ragReserve = Math.max(128, (int) (budget * Math.max(0.15, Math.min(ragBudgetRatio, 0.7))));
        int baseBudget = Math.max(100, budget - ragReserve);
        String fittedBase = estimate(base) > baseBudget ? truncateChars(base, baseBudget) : base;

        // 从全部片段开始向下收缩，确保“片段 + 截断提示 + 闭合标签”整体仍在预算内。
        // 不能在最后做字符级兜底，否则又会切断 </untrusted_retrieval>。
        int keptCount = fragments.size();
        String result = buildRagPrompt(fittedBase, fragments, keptCount);
        while (estimate(result) > budget && keptCount > 0) {
            keptCount--;
            result = buildRagPrompt(fittedBase, fragments, keptCount);
        }
        boolean ragTruncated = keptCount < fragments.size();
        if (keptCount == 0) {
            String omitted = "\n\n【系统提示：知识库检索结果因上下文预算不足已省略，请勿假设未提供的资料。】";
            result = fittedBase + omitted;
            if (estimate(result) > budget) {
                // 只裁剪可信的系统指令，绝不再拼接/裁剪不可信边界。
                result = truncateChars(fittedBase, Math.max(32, budget - estimate(omitted))) + omitted;
            }
        }
        return new PromptFit(result, true, ragTruncated, keptCount, fragments.size());
    }

    /** 按完整片段重建 RAG 提示；片段内部不做任何字符截断。 */
    private String buildRagPrompt(String base, List<String> fragments, int keptCount) {
        if (keptCount <= 0) return base;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < keptCount && i < fragments.size(); i++) {
            if (kept.length() > 0) kept.append("\n").append(ChatService.RAG_FRAGMENT_SEPARATOR).append("\n");
            kept.append(fragments.get(i));
        }
        String note = keptCount < fragments.size()
                ? "\n\n【系统提示：知识库检索结果因上下文预算被截断，仅保留 " + keptCount
                + "/" + fragments.size() + " 个完整片段；如回答依赖被截断内容，请明确说明资料不完整。】"
                : "";
        return base + "\n\n<untrusted_retrieval>\n" + kept + "\n</untrusted_retrieval>" + note;
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
