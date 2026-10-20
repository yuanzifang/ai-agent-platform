package com.platform.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.UserMemory;
import com.platform.agent.mapper.UserMemoryMapper;
import com.platform.common.context.TenantContext;
import com.platform.model.service.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 长期记忆自动生成：对话结束后异步抽取“关于用户”的稳定事实/偏好，
 * 以 source=AUTO 写入 user_memory（用户可见可编辑）；过滤敏感信息与一次性内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    private final UserMemoryMapper memoryMapper;
    private final ChatModelRouter chatModelRouter;
    private final ObjectMapper objectMapper;
    @Qualifier("llmTaskExecutor")
    private final TaskExecutor llmExecutor;

    @Value("${memory.auto-extract.enabled:true}")
    private boolean enabled;
    @Value("${memory.auto-extract.max-items:3}")
    private int maxItems;
    @Value("${memory.auto-extract.max-per-user:50}")
    private int maxPerUser;

    private static final List<String> SENSITIVE = List.of(
            "密码", "passwd", "password", "token", "api key", "密钥", "身份证", "银行卡", "信用卡");

    public void extractAsync(Long tenantId, Long userId, Long agentId, String modelName,
                             String userMessage, String answer) {
        if (!enabled || tenantId == null || userId == null || userMessage == null
                || userMessage.trim().length() < 6) {
            return;
        }
        try {
            llmExecutor.execute(() -> TenantContext.run(tenantId,
                    () -> extract(tenantId, userId, agentId, modelName, userMessage, answer)));
        } catch (Exception e) {
            log.debug("长期记忆抽取提交失败：{}", e.getMessage());
        }
    }

    private void extract(Long tenantId, Long userId, Long agentId, String modelName,
                         String userMessage, String answer) {
        try {
            String prompt = """
                    用户消息：%s
                    助手回答：%s

                    请抽取“关于用户本人”的长期稳定信息（称呼、所在地、职业、长期偏好、目标等），
                    不要抽取一次性问题、临时状态、助手编造的信息、密码/密钥/身份证/银行卡等敏感信息。
                    最多 %d 条，每条不超过 200 字，没有则返回空数组。
                    严格输出 JSON（不要 Markdown）：
                    {"memories":[{"content":"事实内容","category":"PROFILE|PREFERENCE|FACT"}]}
                    """.formatted(userMessage, answer == null ? "" : answer, Math.max(1, maxItems));
            var response = ChatClient.create(chatModelRouter.resolve(modelName)).prompt()
                    .options(chatModelRouter.options(modelName, 0.1, List.of()))
                    .system("你是用户长期记忆抽取器，只输出 JSON。")
                    .user(prompt).call().chatResponse();
            String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) return;
            JsonNode root = objectMapper.readTree(stripFence(text));
            JsonNode memories = root.isArray() ? root : root.path("memories");
            if (!memories.isArray()) return;
            long total = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getTenantId, tenantId)
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getStatus, "ACTIVE"));
            if (total >= maxPerUser) return;
            int added = 0;
            for (JsonNode node : memories) {
                if (added >= maxItems || total + added >= maxPerUser) break;
                String content = node.path("content").asText("").trim();
                if (content.isEmpty() || content.length() > 200 || isSensitive(content)) continue;
                long exists = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                        .eq(UserMemory::getTenantId, tenantId)
                        .eq(UserMemory::getUserId, userId)
                        .eq(UserMemory::getContent, content)
                        .eq(UserMemory::getStatus, "ACTIVE"));
                if (exists > 0) continue;
                UserMemory m = new UserMemory();
                m.setTenantId(tenantId);
                m.setUserId(userId);
                m.setAgentId(agentId);
                m.setCategory(normalizeCategory(node.path("category").asText("FACT")));
                m.setContent(content);
                m.setSource("AUTO");
                m.setStatus("ACTIVE");
                m.setCreatedAt(LocalDateTime.now());
                m.setUpdatedAt(LocalDateTime.now());
                memoryMapper.insert(m);
                added++;
            }
            if (added > 0) log.info("自动生成长期记忆：user={} added={}", userId, added);
        } catch (Exception e) {
            log.debug("长期记忆自动抽取失败（忽略）：{}", e.getMessage());
        }
    }

    private String normalizeCategory(String raw) {
        String category = raw == null ? "FACT" : raw.toUpperCase(Locale.ROOT);
        return List.of("PROFILE", "PREFERENCE", "FACT").contains(category) ? category : "FACT";
    }

    private boolean isSensitive(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return SENSITIVE.stream().anyMatch(lower::contains);
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
