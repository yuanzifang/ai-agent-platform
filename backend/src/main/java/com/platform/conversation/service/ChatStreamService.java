package com.platform.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.agent.service.ChatService;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.entity.ChatAttachment;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.entity.Message;
import com.platform.conversation.mapper.ChatAttachmentMapper;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.mapper.MessageMapper;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正式对话执行（需求文档 3.4 / 7.4）：发布版本快照加载、记忆滚动窗口、RAG、
 * SSE 流式（delta/citation/moderation/usage/done/error）、停止/重新生成、计费落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatModel chatModel;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChatAttachmentMapper attachmentMapper;
    private final AgentRegistryMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final BillingUsageMapper billingMapper;
    private final ChatService chatService;                 // 复用 RAG augmentWithKnowledge
    private final ConversationService conversationService;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;

    private static final int MEMORY_WINDOW = 10;
    /** conversationId → 进行中的流（停止/断开时 dispose 并落 STOPPED）。 */
    private static final Map<Long, Disposable> ACTIVE = new ConcurrentHashMap<>();

    public record SendRequest(String message, Boolean stream, Map<String, Object> variables, List<Long> attachmentIds) {
    }

    /** 发送消息：入口（校验/审核/落用户消息），随后进入流式或非流式生成。 */
    public Object send(Long conversationId, SendRequest req, JwtPrincipal principal) {
        Conversation conv = conversationService.owned(conversationId, principal);
        if (req.message() == null || req.message().isBlank()) {
            throw new BusinessException(400, "消息不能为空");
        }
        AgentRegistry agent = agentMapper.selectById(conv.getAgentId());
        Map<String, Object> config = publishedConfig(agent);
        Map<String, Object> variables = req.variables() == null ? Map.of() : req.variables();
        validateVariables(config.get("variables"), variables);

        // 输入审核（P0：敏感词/注入，BLOCK 拦截并留痕）
        List<ModerationService.Hit> hits = moderationService.moderate("IN", req.message(), conversationId, null);
        boolean blocked = moderationService.blocked(hits);

        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setContent(req.message());
        userMsg.setAttachments(attachmentsJson(conversationId, req.attachmentIds(), userMsg));
        userMsg.setStatus(blocked ? "ERROR" : "SUCCESS");
        userMsg.setAuditedStatus(blocked ? "REVOKED" : "PASS");
        userMsg.setIsAiGenerated(0);
        userMsg.setTenantId(principal.tenantId());
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        if (blocked) {
            return blockedEmitter(conversationId, userMsg, hits);
        }
        if (Boolean.FALSE.equals(req.stream())) {
            return blockingChat(conv, agent, config, userMsg, variables);
        }
        return streamChat(conv, agent, config, userMsg, null, false, hits, variables);
    }

    /** 重新生成：以被替换消息之前的最近 USER 消息重跑，新消息带 parentMessageId。 */
    public Object regenerate(Long conversationId, Long messageId, JwtPrincipal principal) {
        Conversation conv = conversationService.owned(conversationId, principal);
        Message target = conversationService.messageOf(messageId);
        if (target == null || !target.getConversationId().equals(conversationId)
                || !"ASSISTANT".equals(target.getRole())) {
            throw new BusinessException(404, "待重新生成的消息不存在");
        }
        Message lastUser = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .eq(Message::getRole, "USER")
                        .lt(Message::getId, messageId)
                        .orderByDesc(Message::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (lastUser == null) {
            throw new BusinessException(400, "找不到对应的用户消息，无法重新生成");
        }
        AgentRegistry agent = agentMapper.selectById(conv.getAgentId());
        Map<String, Object> config = publishedConfig(agent);
        return streamChat(conv, agent, config, lastUser, target.getId(), true, List.of(), Map.of());
    }

    /** 停止生成（幂等）：断开进行中的流并落 STOPPED。 */
    public void stop(Long conversationId, Long messageId, JwtPrincipal principal) {
        conversationService.owned(conversationId, principal);
        Disposable d = ACTIVE.remove(conversationId);
        if (d != null && !d.isDisposed()) {
            d.dispose(); // doOnCancel 负责落库 STOPPED
        }
    }

    // ---------- 生成主流程 ----------

    @SuppressWarnings("unchecked")
    private Object streamChat(Conversation conv, AgentRegistry agent, Map<String, Object> config,
                              Message userMsg, Long parentMessageId, boolean regenerated,
                              List<ModerationService.Hit> inHits, Map<String, Object> variables) {
        Long tenantId = conv.getTenantId();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        SseEmitter emitter = new SseEmitter(300_000L);

        // RAG 检索在请求线程完成（租户上下文可用）
        String systemPrompt = applyVariables(str(config.get("systemPrompt")), config.get("variables"), variables);
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> kbBindings = knowledgeBindings(conv.getAgentId());
        if (!kbBindings.isEmpty()) {
            systemPrompt = chatService.augmentWithKnowledge(systemPrompt, userMsg.getContent(), kbBindings, citations);
        }
        List<org.springframework.ai.chat.messages.Message> history = history(conv.getId(), userMsg.getId());

        Map<String, Object> modelConfig = config.get("modelConfig") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String modelName = str(modelConfig.getOrDefault("modelName", "deepseek-chat"));
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        Object temperature = modelConfig.get("temperature");
        if (temperature instanceof Number n) options.temperature(n.doubleValue());

        StringBuilder acc = new StringBuilder();
        final String finalSystemPrompt = systemPrompt;
        long[] tokens = {0, 0};

        ChatClient.ChatClientRequestSpec spec = ChatClient.create(chatModel).prompt().options(options.build());
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);
        spec = spec.messages(history).user(userMsg.getContent());
        Flux<org.springframework.ai.chat.model.ChatResponse> flux = spec.stream().chatResponse();

        Runnable persist = () -> TenantContext.run(tenantId, () -> {
            Message ai = newMessage(conv, traceId, parentMessageId, regenerated);
            ai.setContent(acc.toString());
            ai.setStatus("SUCCESS");
            ai.setTokens((int) (tokens[0] + tokens[1]));
            messageMapper.insert(ai);
            conversationService.touch(conv.getId());
            recordUsage(tenantId, conv.getAgentId(), modelName, tokens[0], tokens[1]);
            emitSafe(emitter, "done", Map.of("traceId", traceId, "messageId", ai.getId(),
                    "status", ai.getStatus(),
                    "usage", Map.of("inputTokens", tokens[0], "outputTokens", tokens[1])));
            emitter.complete();
        });

        Disposable disposable = flux.subscribe(
                resp -> {
                    String delta = resp.getResult() == null ? null : resp.getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                        acc.append(delta);
                        emitSafe(emitter, "delta", Map.of("content", delta));
                    }
                    if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                        var u = resp.getMetadata().getUsage();
                        if (u.getPromptTokens() > 0) tokens[0] = u.getPromptTokens();
                        if (u.getCompletionTokens() > 0) tokens[1] = u.getCompletionTokens();
                    }
                },
                err -> {
                    log.error("对话流式失败 conversation={}：{}", conv.getId(), err.getMessage());
                    ACTIVE.remove(conv.getId());
                    TenantContext.run(tenantId, () -> {
                        Message ai = newMessage(conv, traceId, parentMessageId, regenerated);
                        ai.setContent(acc.toString());
                        ai.setStatus("ERROR");
                        messageMapper.insert(ai);
                    });
                    emitSafe(emitter, "error", Map.of("code", 500201, "message", "生成失败：" + err.getMessage()));
                    emitter.complete();
                },
                () -> {
                    ACTIVE.remove(conv.getId());
                    if (tokens[0] == 0 && tokens[1] == 0) {
                        tokens[0] = estimateTokens(userMsg.getContent() + finalSystemPrompt);
                        tokens[1] = estimateTokens(acc.toString());
                    }
                    emitSafe(emitter, "usage", Map.of("inputTokens", tokens[0], "outputTokens", tokens[1]));
                    persist.run();
                });

        // 停止/断开：取消上游，落 STOPPED（幂等保护）
        Runnable cancel = () -> {
            if (ACTIVE.remove(conv.getId()) != null) {
                if (tokens[0] == 0 && tokens[1] == 0) {
                    tokens[0] = estimateTokens(userMsg.getContent());
                    tokens[1] = estimateTokens(acc.toString());
                }
                TenantContext.run(tenantId, () -> {
                    Message ai = newMessage(conv, traceId, parentMessageId, regenerated);
                    ai.setContent(acc.toString());
                    ai.setStatus("STOPPED");
                    ai.setTokens((int) (tokens[0] + tokens[1]));
                    messageMapper.insert(ai);
                    conversationService.touch(conv.getId());
                    recordUsage(tenantId, conv.getAgentId(), modelName, tokens[0], tokens[1]);
                });
                emitSafe(emitter, "done", Map.of("traceId", traceId, "status", "STOPPED",
                        "usage", Map.of("inputTokens", tokens[0], "outputTokens", tokens[1])));
                emitter.complete();
            }
        };
        emitter.onCompletion(() -> { });
        emitter.onTimeout(() -> { disposable.dispose(); cancel.run(); });
        emitter.onError(t -> { disposable.dispose(); cancel.run(); });

        ACTIVE.put(conv.getId(), new Disposable() {
            @Override public void dispose() { disposable.dispose(); cancel.run(); }
            @Override public boolean isDisposed() { return disposable.isDisposed(); }
        });

        // 先发 citation / moderation 事件再进入增量
        for (Map<String, Object> c : citations) emitSafe(emitter, "citation", c);
        for (ModerationService.Hit h : inHits) emitSafe(emitter, "moderation", moderationService.toEvent(h));
        emitSafe(emitter, "meta", Map.of("traceId", traceId, "conversationId", conv.getId(),
                "userMessageId", userMsg.getId()));
        return emitter;
    }

    /** 非流式：同步调用，返回 JSON（需求文档 7.3）。 */
    private Map<String, Object> blockingChat(Conversation conv, AgentRegistry agent,
                                             Map<String, Object> config, Message userMsg,
                                             Map<String, Object> variables) {
        Long tenantId = conv.getTenantId();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String systemPrompt = applyVariables(str(config.get("systemPrompt")), config.get("variables"), variables);
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> kbBindings = knowledgeBindings(conv.getAgentId());
        if (!kbBindings.isEmpty()) {
            systemPrompt = chatService.augmentWithKnowledge(systemPrompt, userMsg.getContent(), kbBindings, citations);
        }
        List<org.springframework.ai.chat.messages.Message> history = history(conv.getId(), userMsg.getId());
        Map<String, Object> modelConfig = asMap(config.get("modelConfig"));
        String modelName = str(modelConfig.getOrDefault("modelName", "deepseek-chat"));
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        if (modelConfig.get("temperature") instanceof Number n) options.temperature(n.doubleValue());

        ChatClient.ChatClientRequestSpec spec = ChatClient.create(chatModel).prompt().options(options.build());
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);
        var resp = spec.messages(history).user(userMsg.getContent()).call().chatResponse();
        String answer = resp.getResult().getOutput().getText();
        var usage = resp.getMetadata().getUsage();
        long in = usage == null ? 0 : usage.getPromptTokens();
        long out = usage == null ? 0 : usage.getCompletionTokens();

        Message ai = newMessage(conv, traceId, null, false);
        ai.setContent(answer == null ? "" : answer);
        ai.setStatus("SUCCESS");
        ai.setTokens((int) (in + out));
        messageMapper.insert(ai);
        conversationService.touch(conv.getId());
        recordUsage(tenantId, conv.getAgentId(), modelName, in, out);

        // 输出审核（P0 留痕）
        moderationService.moderate("OUT", answer, conv.getId(), ai.getId());

        return Map.of("answer", ai.getContent(), "traceId", traceId, "messageId", ai.getId(),
                "usage", Map.of("inputTokens", in, "outputTokens", out), "citations", citations);
    }

    /** 输入命中 BLOCK：不调用模型，返回仅含 moderation/error/done 的 SSE。 */
    private SseEmitter blockedEmitter(Long conversationId, Message userMsg, List<ModerationService.Hit> hits) {
        SseEmitter emitter = new SseEmitter(10_000L);
        for (ModerationService.Hit h : hits) {
            emitSafe(emitter, "moderation", moderationService.toEvent(h));
        }
        emitSafe(emitter, "error", Map.of("code", 700001, "message", "消息包含违规内容，已被拦截"));
        emitSafe(emitter, "done", Map.of("status", "BLOCKED"));
        emitter.complete();
        return emitter;
    }

    // ---------- 辅助 ----------

    private Message newMessage(Conversation conv, String traceId, Long parentMessageId, boolean regenerated) {
        Message ai = new Message();
        ai.setConversationId(conv.getId());
        ai.setTraceId(traceId);
        ai.setRole("ASSISTANT");
        ai.setParentMessageId(parentMessageId);
        ai.setIsRegenerated(regenerated ? 1 : 0);
        ai.setAuditedStatus("PASS");
        ai.setIsAiGenerated(1);
        ai.setTenantId(conv.getTenantId());
        ai.setCreatedAt(LocalDateTime.now());
        return ai;
    }

    /** 记忆滚动窗口：最近 N 条已成功消息（不含本次 userMsg）。 */
    private List<org.springframework.ai.chat.messages.Message> history(Long conversationId, Long beforeMessageId) {
        List<Message> recent = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .in(Message::getStatus, "SUCCESS", "STOPPED")
                .in(Message::getRole, "USER", "ASSISTANT")
                .lt(beforeMessageId != null, Message::getId, beforeMessageId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + MEMORY_WINDOW));
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message m = recent.get(i);
            history.add("USER".equals(m.getRole()) ? new UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()));
        }
        return history;
    }

    /** Agent 绑定的知识库（enabled=1），结构同调试对话 config.knowledgeBases。 */
    private List<Map<String, Object>> knowledgeBindings(Long agentId) {
        List<AgentKnowledgeBase> rows = agentKbMapper.selectList(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .eq(AgentKnowledgeBase::getAgentId, agentId)
                .eq(AgentKnowledgeBase::getEnabled, 1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentKnowledgeBase row : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("knowledgeBaseId", row.getKnowledgeBaseId());
            b.put("enabled", true);
            b.put("retrievalConfig", fromJson(row.getRetrievalConfig()));
            result.add(b);
        }
        return result;
    }

    /** 最新发布版本快照；无版本则 400（对话必须基于已发布配置）。 */
    private Map<String, Object> publishedConfig(AgentRegistry agent) {
        AgentVersion v = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agent.getId())
                        .orderByDesc(AgentVersion::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (v == null) {
            throw new BusinessException(400, "Agent 尚无发布版本，无法对话");
        }
        return fromJson(v.getSnapshot());
    }

    private String attachmentsJson(Long conversationId, List<Long> attachmentIds, Message userMsg) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return null;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Long id : attachmentIds) {
            ChatAttachment a = attachmentMapper.selectById(id);
            if (a == null || !a.getConversationId().equals(conversationId)) {
                throw new BusinessException(400, "附件不存在或不属于当前会话：" + id);
            }
            a.setMessageId(userMsg.getId());
            attachmentMapper.updateById(a);
            list.add(Map.of("attachmentId", a.getId(), "fileName", a.getFileName(),
                    "objectName", a.getObjectName(), "contentType", a.getContentType() == null ? "" : a.getContentType(),
                    "sizeBytes", a.getSizeBytes()));
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private void recordUsage(Long tenantId, Long agentId, String modelName, long in, long out) {
        BillingUsage u = new BillingUsage();
        u.setTenantId(tenantId);
        u.setAgentId(agentId);
        u.setSource("chat");
        u.setModelName(modelName);
        u.setInputTokens(in);
        u.setOutputTokens(out);
        u.setStatDate(LocalDate.now());
        u.setCreatedAt(LocalDateTime.now());
        billingMapper.insert(u);
    }

    private void emitSafe(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）event={}：{}", event, e.getMessage());
        }
    }

    /** 流式响应未带 usage 时的粗估（中文≈1 token/字，英文≈4 字符/token 折中）。 */
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 2);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 必填变量校验（需求 3.1.3(9)）：required 且无默认值且未传入 → 400。 */
    @SuppressWarnings("unchecked")
    private void validateVariables(Object varDefs, Map<String, Object> values) {
        if (!(varDefs instanceof List<?> defs)) return;
        for (Object o : defs) {
            Map<String, Object> d = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String key = str(d.get("key"));
            if (key == null || key.isBlank()) continue;
            Object val = values.get(key);
            boolean required = Boolean.TRUE.equals(d.get("required")) || "true".equals(str(d.get("required")));
            boolean hasDefault = d.get("defaultValue") != null && !String.valueOf(d.get("defaultValue")).isBlank();
            if (required && (val == null || String.valueOf(val).isBlank()) && !hasDefault) {
                throw new BusinessException(400, "缺少必填变量：" + key);
            }
        }
    }

    /** {{key}} 占位符替换：传入值优先，缺省回落 defaultValue（提示词运行时替换，需求 3.1.3(9)）。 */
    @SuppressWarnings("unchecked")
    private String applyVariables(String text, Object varDefs, Map<String, Object> values) {
        if (text == null || !(varDefs instanceof List<?> defs)) return text;
        for (Object o : defs) {
            Map<String, Object> d = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String key = str(d.get("key"));
            if (key == null || key.isBlank()) continue;
            Object v = values != null && values.containsKey(key) && values.get(key) != null
                    ? values.get(key) : d.get("defaultValue");
            if (v != null) text = text.replace("{{" + key + "}}", String.valueOf(v));
        }
        return text;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
