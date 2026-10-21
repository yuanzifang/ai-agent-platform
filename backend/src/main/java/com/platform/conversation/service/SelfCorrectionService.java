package com.platform.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.model.service.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 自我纠正：回答生成后由审查模型检查事实性/切题/完整性，
 * 低于阈值时给出修订答案；调用方按 Agent 配置控制 1~3 轮，避免无限循环与成本失控。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfCorrectionService {

    private final ChatModelRouter chatModelRouter;
    private final ObjectMapper objectMapper;

    @Value("${self-correction.threshold:0.7}")
    private double threshold;

    public record ReviewResult(double score, String issues, String revised,
                               long inputTokens, long outputTokens) {
    }

    public ReviewResult review(String modelName, String question, String answer) {
        if (answer == null || answer.isBlank()) return null;
        String prompt = """
                用户问题：%s
                待审查回答：%s

                请检查回答是否切题、是否编造事实、是否完整。严格输出 JSON：
                {"score":0到1,"issues":"问题描述（无则空字符串）","revised":"修订后的完整回答"}
                若回答没有明显问题，revised 必须与原文完全一致。
                """.formatted(question, answer);
        try {
            var response = ChatClient.create(chatModelRouter.resolve(modelName)).prompt()
                    .options(chatModelRouter.options(modelName, 0.1, List.of()))
                    .system("你是严格的质量审查员，只输出 JSON，不要输出 Markdown 代码块。")
                    .user(prompt).call().chatResponse();
            String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) return null;
            String json = stripFence(text);
            JsonNode node = objectMapper.readTree(json);
            double score = node.path("score").asDouble(1.0);
            String issues = node.path("issues").asText("");
            String revised = node.path("revised").asText("");
            long in = 0, out = 0;
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                in = response.getMetadata().getUsage().getPromptTokens();
                out = response.getMetadata().getUsage().getCompletionTokens();
            }
            if (score < threshold && !revised.isBlank() && !revised.equals(answer)) {
                log.info("自我纠正触发：model={} score={} issues={}", modelName, score, issues);
                return new ReviewResult(score, issues, revised, in, out);
            }
            return new ReviewResult(score, issues, answer, in, out);
        } catch (Exception e) {
            log.warn("自我纠正审查失败（保留原回答）：{}", e.getMessage());
            return null;
        }
    }

    private String stripFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) return trimmed.substring(first + 1, last).trim();
        }
        return trimmed;
    }
}
