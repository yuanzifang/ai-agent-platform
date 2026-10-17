package com.platform.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.entity.ChatAttachment;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.entity.ConversationShare;
import com.platform.conversation.entity.Feedback;
import com.platform.conversation.entity.Message;
import com.platform.conversation.mapper.ChatAttachmentMapper;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.mapper.ConversationShareMapper;
import com.platform.conversation.mapper.FeedbackMapper;
import com.platform.conversation.mapper.MessageMapper;
import com.platform.knowledge.service.StorageService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话管理（需求文档 3.4 / 6.18–6.25）：归属校验、软删、反馈、分享只读。
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChatAttachmentMapper attachmentMapper;
    private final FeedbackMapper feedbackMapper;
    private final ConversationShareMapper shareMapper;
    private final AgentRegistryMapper agentMapper;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list(String keyword, long page, long size, JwtPrincipal principal) {
        Page<Conversation> result = conversationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, principal.userId())
                        .ne(Conversation::getStatus, "DELETED")
                        .like(keyword != null && !keyword.isBlank(), Conversation::getTitle, keyword)
                        .orderByDesc(Conversation::getLastActiveAt));
        Map<Long, String> agentNames = agentMapper.selectBatchIds(
                        result.getRecords().stream().map(Conversation::getAgentId).distinct().toList())
                .stream().collect(Collectors.toMap(AgentRegistry::getId, a -> a.getName() == null ? "" : a.getName()));
        List<Map<String, Object>> list = result.getRecords().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("sessionId", c.getSessionId());
            m.put("agentId", c.getAgentId());
            m.put("agentName", agentNames.getOrDefault(c.getAgentId(), ""));
            m.put("title", c.getTitle());
            m.put("lastActiveAt", c.getLastActiveAt());
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", page, "pageSize", size);
    }

    public Map<String, Object> create(Long agentId, String title, JwtPrincipal principal) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) {
            throw new BusinessException(404, "Agent 不存在");
        }
        if (!"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(400, "Agent 未发布，无法发起对话（请先在列表页发布）");
        }
        Conversation c = new Conversation();
        c.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        c.setAgentId(agentId);
        c.setUserId(principal.userId());
        c.setTitle(title == null || title.isBlank() ? agent.getName() : title);
        c.setSource("web");
        c.setStatus("ACTIVE");
        c.setTenantId(principal.tenantId());
        c.setLastActiveAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        conversationMapper.insert(c);
        return Map.of("id", c.getId(), "sessionId", c.getSessionId());
    }

    public Conversation owned(Long id, JwtPrincipal principal) {
        Conversation c = conversationMapper.selectById(id);
        if (c == null || "DELETED".equals(c.getStatus())) {
            throw new BusinessException(404, "会话不存在");
        }
        if (!c.getUserId().equals(principal.userId())) {
            throw new BusinessException(403, "无权访问该会话");
        }
        return c;
    }

    /** 详情：会话 + 消息（含附件与我本人的反馈状态）。 */
    public Map<String, Object> detail(Long id, JwtPrincipal principal) {
        Conversation c = owned(id, principal);
        AgentRegistry agent = agentMapper.selectById(c.getAgentId());
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, id)
                .in(Message::getStatus, "SUCCESS", "STOPPED")
                .orderByAsc(Message::getId));
        Map<Long, String> myFeedback = feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>()
                        .eq(Feedback::getConversationId, id).eq(Feedback::getUserId, principal.userId()))
                .stream().collect(Collectors.toMap(Feedback::getMessageId, Feedback::getType, (a, b) -> b));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", c.getId());
        result.put("sessionId", c.getSessionId());
        result.put("agentId", c.getAgentId());
        result.put("agentName", agent == null ? "" : agent.getName());
        result.put("title", c.getTitle());
        result.put("status", c.getStatus());
        result.put("openingStatement", agent != null && agent.getOpeningStatement() != null
                ? agent.getOpeningStatement() : "");
        result.put("messages", messages.stream().map(msg -> toView(msg, myFeedback)).toList());
        return result;
    }

    private Map<String, Object> toView(Message m, Map<Long, String> myFeedback) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId());
        v.put("role", m.getRole());
        v.put("content", m.getContent());
        v.put("status", m.getStatus());
        v.put("isAiGenerated", m.getIsAiGenerated() != null && m.getIsAiGenerated() == 1);
        v.put("tokens", m.getTokens());
        v.put("createdAt", m.getCreatedAt());
        v.put("parentMessageId", m.getParentMessageId());
        v.put("feedback", myFeedback.get(m.getId()));
        v.put("attachments", parseAttachments(m.getAttachments()));
        return v;
    }

    private List<Map<String, Object>> parseAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 软删会话，级联清理附件对象与记录（需求文档 7.2）。 */
    public void delete(Long id, JwtPrincipal principal) {
        owned(id, principal);
        List<ChatAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<ChatAttachment>().eq(ChatAttachment::getConversationId, id));
        for (ChatAttachment a : attachments) {
            storageService.remove(a.getObjectName());
            attachmentMapper.deleteById(a.getId());
        }
        Conversation patch = new Conversation();
        patch.setId(id);
        patch.setStatus("DELETED");
        conversationMapper.updateById(patch);
    }

    public void touch(Long id) {
        Conversation patch = new Conversation();
        patch.setId(id);
        patch.setLastActiveAt(LocalDateTime.now());
        conversationMapper.updateById(patch);
    }

    /** 保存会话附件（multipart → MinIO → chat_attachment）。 */
    public Map<String, Object> saveAttachment(Long conversationId, MultipartFilePart file, JwtPrincipal principal) {
        owned(conversationId, principal);
        String objectName = "%d/chat/%d/%s/%s".formatted(principal.tenantId(), conversationId,
                UUID.randomUUID().toString().replace("-", "").substring(0, 12), file.fileName());
        storageService.put(objectName, file.bytes(), file.contentType());
        ChatAttachment a = new ChatAttachment();
        a.setConversationId(conversationId);
        a.setObjectName(objectName);
        a.setFileName(file.fileName());
        a.setSizeBytes((long) file.bytes().length);
        a.setContentType(file.contentType());
        a.setTenantId(principal.tenantId());
        a.setCreatedAt(LocalDateTime.now());
        attachmentMapper.insert(a);
        return Map.of("attachmentId", a.getId(), "fileName", a.getFileName(),
                "sizeBytes", a.getSizeBytes(), "contentType", a.getContentType());
    }

    /** 仅可对 ASSISTANT 消息反馈（需求文档 6.22）。 */
    public void feedback(Long conversationId, Long messageId, String type, String comment, JwtPrincipal principal) {
        owned(conversationId, principal);
        Message m = messageMapper.selectById(messageId);
        if (m == null || !m.getConversationId().equals(conversationId)) {
            throw new BusinessException(404, "消息不存在");
        }
        if (!"ASSISTANT".equals(m.getRole())) {
            throw new BusinessException(400, "仅支持对 AI 回复进行反馈");
        }
        if (!"UP".equals(type) && !"DOWN".equals(type)) {
            throw new BusinessException(400, "反馈类型仅支持 UP/DOWN");
        }
        Feedback f = new Feedback();
        f.setMessageId(messageId);
        f.setConversationId(conversationId);
        f.setTraceId(m.getTraceId());
        f.setUserId(principal.userId());
        f.setType(type);
        f.setComment(comment);
        f.setTenantId(principal.tenantId());
        f.setCreatedAt(LocalDateTime.now());
        feedbackMapper.insert(f);
    }

    public Map<String, Object> createShare(Long conversationId, LocalDateTime expiresAt, JwtPrincipal principal) {
        owned(conversationId, principal);
        ConversationShare s = new ConversationShare();
        s.setConversationId(conversationId);
        s.setToken(UUID.randomUUID().toString().replace("-", ""));
        s.setExpiresAt(expiresAt == null ? LocalDateTime.now().plusDays(7) : expiresAt);
        s.setCreatedBy(principal.userId());
        s.setTenantId(principal.tenantId());
        s.setCreatedAt(LocalDateTime.now());
        shareMapper.insert(s);
        return Map.of("shareToken", s.getToken(), "expiresAt", s.getExpiresAt());
    }

    /** 免登录只读回看（需求文档 8.1 分享鉴权）：token 校验后以所属租户身份加载。 */
    public Map<String, Object> shareView(String token) {
        ConversationShare s = shareMapper.selectByToken(token);
        if (s == null || s.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(404, "分享链接不存在或已过期");
        }
        return TenantContext.runWith(s.getTenantId(), () -> {
            Conversation c = conversationMapper.selectById(s.getConversationId());
            if (c == null) throw new BusinessException(404, "会话不存在");
            AgentRegistry agent = agentMapper.selectById(c.getAgentId());
            List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, c.getId())
                    .in(Message::getStatus, "SUCCESS", "STOPPED")
                    .orderByAsc(Message::getId));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", c.getTitle());
            result.put("agentName", agent == null ? "" : agent.getName());
            result.put("expiresAt", s.getExpiresAt());
            result.put("messages", messages.stream()
                    .map(m -> toView(m, Map.of())).toList());
            return result;
        });
    }

    public Message messageOf(Long messageId) {
        return messageMapper.selectById(messageId);
    }

    public record MultipartFilePart(String fileName, String contentType, byte[] bytes) {
    }
}
