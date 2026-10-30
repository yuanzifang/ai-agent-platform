package com.platform.agent.service;

import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.service.ChatModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ContextWindowRagTest {

    private final ContextWindowService service = new ContextWindowService(
            mock(ModelMapper.class), mock(ChatModelRouter.class),
            mock(BillingUsageMapper.class), mock(PricingService.class));

    @Test
    void keepsUntrustedBoundaryAndCompleteFragmentsWhenBudgetTight() {
        StringBuilder fragments = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            fragments.append("fragment-").append(i).append("-").append("x".repeat(100)).append("\n---\n");
        }
        String prompt = "system instruction\n\n<untrusted_retrieval>\n"
                + fragments + "\n</untrusted_retrieval>\n\n长期记忆：用户偏好中文";
        ContextWindowService.PromptFit fit = ReflectionTestUtils.invokeMethod(
                service, "fitSystemPrompt", prompt, 600, "test-model");

        assertTrue(fit.truncated());
        assertTrue(fit.ragTruncated());
        assertTrue(fit.text().contains("<untrusted_retrieval>"));
        assertTrue(fit.text().contains("</untrusted_retrieval>"));
        assertTrue(fit.text().contains("完整片段"));
        assertFalse(fit.text().contains("fragment-19"));
        assertTrue(fit.text().contains("长期记忆：用户偏好中文"), "RAG 之后的长期记忆不能被丢弃");
    }

    @Test
    void malformedBoundaryIsRemovedInsteadOfPassthrough() {
        String prompt = "base instructions\n\n<untrusted_retrieval>\nsecret untrusted text";
        ContextWindowService.PromptFit fit = ReflectionTestUtils.invokeMethod(
                service, "fitSystemPrompt", prompt, 400, "test-model");

        assertTrue(fit.ragTruncated());
        assertFalse(fit.text().contains("untrusted text"));
        assertFalse(fit.text().contains("<untrusted_retrieval>"));
    }
    @Test
    void markerKeepsHorizontalRuleInsideCompleteFragment() {
        String prompt = "system\n\n<untrusted_retrieval>\n"
                + ChatService.RAG_FRAGMENT_SEPARATOR + "\nfragment-with\n---\nline\n"
                + "</untrusted_retrieval>";
        ContextWindowService.PromptFit fit = ReflectionTestUtils.invokeMethod(
                service, "fitSystemPrompt", prompt, 1000, "test-model");

        assertTrue(fit.text().contains("fragment-with\n---\nline"),
                "正文里的 Markdown 水平线不能被误认为片段分隔符");
    }

}
