package com.platform.agent.service;

import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.billing.service.QuotaService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.model.entity.ModelEntity;
import com.platform.model.mapper.ModelMapper;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词优化（Agent 编辑）：调用对话模型重写系统提示词，保留 {{变量}} 与原始意图；
 * 计入用量（source=intelligent），受每日 Token/余额配额约束；不写入正式 Trace（调试/智能生成口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptOptimizeService {

    private final com.platform.model.service.ChatModelRouter chatModelRouter;
    private final ModelMapper modelMapper;
    private final QuotaService quotaService;
    private final ContextWindowService contextWindow;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;

    private static final String SYSTEM = """
            你是资深提示词工程师。请优化用户提供的 Agent 系统提示词。
            要求：
            1. 保留原始业务意图、语言和所有 {{变量}} 占位符，不得编造事实；
            2. 结构清晰：角色定位、职责边界、回答要求、异常/不确定时的处理；
            3. 明确“先结论后细节”、禁止编造、必要时引导人工；
            4. 不要输出解释、标题或 Markdown 代码块，只输出优化后的提示词正文；
            5. 长度适度（通常 200-800 字），避免空话。
            """;

    public Map<String, Object> optimize(Map<String, Object> req, JwtPrincipal principal) {
        String prompt = str(req.get("prompt"));
        if (prompt == null || prompt.isBlank()) throw new BusinessException(400, "请先填写提示词");
        if (prompt.length() > 20000) throw new BusinessException(400, "提示词过长，请先精简");
        String modelName = resolveModel(str(req.get("modelName")));
        String instruction = str(req.get("instruction"));
        String style = str(req.get("style"));

        StringBuilder user = new StringBuilder();
        user.append("当前提示词：\n").append(prompt);
        if (notBlank(str(req.get("name")))) user.append("\n\nAgent 名称：").append(str(req.get("name")));
        if (notBlank(str(req.get("description")))) user.append("\nAgent 描述：").append(str(req.get("description")));
        if (notBlank(style)) user.append("\n期望风格：").append(style);
        if (notBlank(instruction)) user.append("\n额外要求：").append(instruction);

        quotaService.checkTokensToday(Math.max(300, prompt.length() / 2 + 400));
        String system = contextWindow.fitPrompt(modelName, SYSTEM, user.toString());

        OpenAiChatOptions options = OpenAiChatOptions.builder().model(modelName).temperature(0.3).build();
        long start = System.currentTimeMillis();
        var response = ChatClient.create(chatModelRouter.resolve(modelName)).prompt().options(options)
                .system(system).user(user.toString()).call().chatResponse();
        long latency = System.currentTimeMillis() - start;
        String optimized = response.getResult() == null ? "" : response.getResult().getOutput().getText();
        optimized = clean(optimized);
        if (optimized.isBlank()) throw new BusinessException(502, "模型未返回优化结果，请重试");
        long in = 0, out = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            in = response.getMetadata().getUsage().getPromptTokens();
            out = response.getMetadata().getUsage().getCompletionTokens();
        }
        recordUsage(modelName, in, out);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("optimizedPrompt", optimized);
        result.put("modelName", modelName);
        result.put("latencyMs", latency);
        result.put("usage", Map.of("inputTokens", in, "outputTokens", out));
        return result;
    }

    private String resolveModel(String requested) {
        Long tenantId = TenantContext.getTenantId();
        if (requested != null && !requested.isBlank()) {
            ModelEntity m = modelMapper.findForPricing(requested, tenantId);
            if (m != null && "CHAT".equalsIgnoreCase(m.getPurpose())) return requested;
        }
        ModelEntity def = modelMapper.findDefaultChat(tenantId);
        return def == null ? "deepseek-flash" : def.getModelName();
    }

    private void recordUsage(String modelName, long in, long out) {
        try {
            Long tenantId = TenantContext.getTenantId();
            BillingUsage u = new BillingUsage();
            u.setTenantId(tenantId);
            u.setSource("intelligent");
            u.setModelName(modelName);
            u.setInputTokens(in);
            u.setOutputTokens(out);
            u.setCost(pricingService.cost(tenantId, modelName, in, out));
            u.setStatDate(LocalDate.now());
            u.setCreatedAt(LocalDateTime.now());
            billingMapper.insert(u);
        } catch (Exception e) {
            log.debug("提示词优化用量记录失败：{}", e.getMessage());
        }
    }

    /** 去掉模型可能附带的代码块/前缀。 */
    private String clean(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "");
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        t = t.replaceFirst("^(优化后的)?提示词[:：]\\s*", "");
        return t.trim();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
