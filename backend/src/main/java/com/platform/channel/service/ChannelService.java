package com.platform.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.channel.entity.AgentChannel;
import com.platform.channel.entity.ChannelMessage;
import com.platform.channel.mapper.AgentChannelMapper;
import com.platform.channel.mapper.ChannelMessageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.common.security.NetworkPolicyService;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.service.ChatStreamService;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 渠道：入站消息解析（Webhook/Slack/钉钉/飞书/企微）→ 复用对话主链路 → 出站格式化回复。
 * 公开入站接口按 token 鉴权；出站 URL 走 SSRF 校验；消息与用量全程留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final List<String> TYPES = List.of("WEBHOOK", "SLACK", "DINGTALK", "FEISHU", "WECOM");

    private final AgentChannelMapper channelMapper;
    private final ChannelMessageMapper messageMapper;
    private final AgentRegistryMapper agentMapper;
    private final ConversationMapper conversationMapper;
    private final SysUserMapper userMapper;
    private final ChatStreamService chatStreamService;
    private final NetworkPolicyService networkPolicy;
    private final CryptoService cryptoService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // ---------- 管理 ----------

    public List<Map<String, Object>> list(Long agentId) {
        requireAgent(agentId);
        return channelMapper.selectList(new LambdaQueryWrapper<AgentChannel>()
                        .eq(AgentChannel::getAgentId, agentId).orderByDesc(AgentChannel::getId))
                .stream().map(this::view).toList();
    }

    public Map<String, Object> create(Long agentId, Map<String, Object> req, JwtPrincipal principal) {
        AgentRegistry agent = requireAgent(agentId);
        String type = normalizeType(str(req.get("type")));
        String name = str(req.get("name"));
        if (name == null || name.isBlank()) name = type + " 渠道";
        AgentChannel c = new AgentChannel();
        c.setAgentId(agentId);
        c.setTenantId(agent.getTenantId());
        c.setName(name.trim());
        c.setType(type);
        c.setToken(UUID.randomUUID().toString().replace("-", ""));
        applyOutbound(c, req);
        c.setStatus("ENABLED");
        c.setCreatedBy(principal.userId());
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        channelMapper.insert(c);
        auditService.record(principal.userId(), "channel.create", "agent_channel",
                String.valueOf(c.getId()), "SUCCESS", Map.of("agentId", agentId, "type", type));
        return view(c);
    }

    public Map<String, Object> update(Long agentId, Long id, Map<String, Object> req, JwtPrincipal principal) {
        requireAgent(agentId);
        AgentChannel c = owned(agentId, id);
        if (req.containsKey("name") && str(req.get("name")) != null) c.setName(str(req.get("name")).trim());
        if (req.containsKey("type")) c.setType(normalizeType(str(req.get("type"))));
        if (req.containsKey("status")) {
            String status = str(req.get("status"));
            c.setStatus("DISABLED".equalsIgnoreCase(status) ? "DISABLED" : "ENABLED");
        }
        applyOutbound(c, req);
        c.setUpdatedAt(LocalDateTime.now());
        channelMapper.updateById(c);
        auditService.record(principal.userId(), "channel.update", "agent_channel",
                String.valueOf(id), "SUCCESS", Map.of("agentId", agentId));
        return view(c);
    }

    public void delete(Long agentId, Long id, JwtPrincipal principal) {
        requireAgent(agentId);
        AgentChannel c = owned(agentId, id);
        messageMapper.delete(new LambdaQueryWrapper<ChannelMessage>().eq(ChannelMessage::getChannelId, id));
        channelMapper.deleteById(id);
        auditService.record(principal.userId(), "channel.delete", "agent_channel",
                String.valueOf(id), "SUCCESS", Map.of("agentId", agentId, "name", c.getName()));
    }

    public Map<String, Object> test(Long agentId, Long id, JwtPrincipal principal) {
        requireAgent(agentId);
        AgentChannel c = owned(agentId, id);
        if (c.getOutboundUrl() == null || c.getOutboundUrl().isBlank()) {
            throw new BusinessException(400, "该渠道未配置出站 Webhook，无法测试");
        }
        long start = System.currentTimeMillis();
        try {
            sendOutbound(c, "这是一条来自 AI Agent 平台的测试消息。", "test");
            return Map.of("success", true, "latencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return Map.of("success", false, "latencyMs", System.currentTimeMillis() - start,
                    "message", rootMessage(e));
        }
    }

    public Map<String, Object> messages(Long agentId, Long id, long page, long pageSize) {
        requireAgent(agentId);
        owned(agentId, id);
        Page<ChannelMessage> p = messageMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 50)),
                new LambdaQueryWrapper<ChannelMessage>()
                        .eq(ChannelMessage::getChannelId, id)
                        .orderByDesc(ChannelMessage::getId));
        return Map.of("list", p.getRecords(), "total", p.getTotal(), "page", page, "pageSize", pageSize);
    }

    // ---------- 入站 ----------

    public Map<String, Object> handleInbound(String token, Map<String, Object> payload) {
        AgentChannel channel = channelMapper.findByTokenIgnoreTenant(token);
        if (channel == null) throw new BusinessException(404, "渠道不存在");
        if (!"ENABLED".equals(channel.getStatus())) throw new BusinessException(403, "渠道已停用");
        // 飞书 URL 校验
        if ("url_verification".equals(str(payload.get("type"))) && payload.get("challenge") != null) {
            return Map.of("challenge", payload.get("challenge"));
        }
        String text = extractText(payload);
        if (text == null || text.isBlank()) throw new BusinessException(400, "未识别到消息文本");
        String externalUserId = extractUserId(payload);
        return TenantContext.runWith(channel.getTenantId(), () -> {
            AgentRegistry agent = agentMapper.selectById(channel.getAgentId());
            if (agent == null || !"PUBLISHED".equals(agent.getStatus())) {
                throw new BusinessException(403, "Agent 未发布");
            }
            Long serviceUserId = serviceUser(agent);
            JwtPrincipal principal = new JwtPrincipal(serviceUserId, channel.getTenantId(), false,
                    List.of(), List.of());
            String sessionId = "ch_" + channel.getId()
                    + (externalUserId == null || externalUserId.isBlank() ? "" : "_" + externalUserId);
            Conversation conversation = resolveConversation(channel, agent, sessionId, externalUserId, serviceUserId);
            record(channel, "IN", externalUserId, sessionId, truncate(text, 8000), "SUCCESS", null);
            Object result;
            try {
                result = chatStreamService.sendWithSource(conversation.getId(),
                        new ChatStreamService.SendRequest(text, false, Map.of(), null), principal, "channel");
            } catch (Exception e) {
                record(channel, "OUT", externalUserId, sessionId, null, "ERROR", rootMessage(e));
                throw new BusinessException(502, "渠道对话失败：" + rootMessage(e));
            }
            if (!(result instanceof Map<?, ?> map)) throw new BusinessException(500, "渠道对话失败");
            String answer = str(map.get("answer"));
            String traceId = str(map.get("traceId"));
            boolean outboundSent = false;
            String outboundError = null;
            if (channel.getOutboundUrl() != null && !channel.getOutboundUrl().isBlank()) {
                try {
                    sendOutbound(channel, answer == null ? "" : answer, sessionId);
                    outboundSent = true;
                } catch (Exception e) {
                    outboundError = rootMessage(e);
                    log.warn("渠道出站失败 channel={}：{}", channel.getId(), outboundError);
                }
            }
            record(channel, "OUT", externalUserId, sessionId, truncate(answer, 8000),
                    outboundError == null ? "SUCCESS" : "ERROR", outboundError);
            auditService.record(0L, "channel.message", "agent_channel", String.valueOf(channel.getId()),
                    "SUCCESS", Map.of("agentId", agent.getId(), "sessionId", sessionId,
                            "outbound", outboundSent));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("answer", answer == null ? "" : answer);
            r.put("sessionId", sessionId);
            r.put("conversationId", conversation.getId());
            r.put("traceId", traceId);
            r.put("outboundSent", outboundSent);
            if (outboundError != null) r.put("outboundError", outboundError);
            return r;
        });
    }

    // ---------- 辅助 ----------

    private AgentRegistry requireAgent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        return agent;
    }

    private AgentChannel owned(Long agentId, Long id) {
        AgentChannel c = channelMapper.selectById(id);
        if (c == null || !c.getAgentId().equals(agentId)) throw new BusinessException(404, "渠道不存在");
        return c;
    }

    private void applyOutbound(AgentChannel c, Map<String, Object> req) {
        if (req.containsKey("outboundUrl")) {
            String url = str(req.get("outboundUrl"));
            if (url != null && !url.isBlank()) {
                networkPolicy.validateHttpUrl(url, "出站 Webhook");
                c.setOutboundUrl(url.trim());
            } else {
                c.setOutboundUrl(null);
            }
        }
        if (req.containsKey("secret")) {
            String secret = str(req.get("secret"));
            c.setSecret(secret == null || secret.isBlank() ? null : cryptoService.encrypt(secret.trim()));
        }
        if (req.containsKey("config")) {
            try {
                c.setConfigJson(req.get("config") == null ? null : objectMapper.writeValueAsString(req.get("config")));
            } catch (Exception ignored) {
            }
        }
    }

    private Map<String, Object> view(AgentChannel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("agentId", c.getAgentId());
        m.put("name", c.getName());
        m.put("type", c.getType());
        m.put("token", c.getToken());
        m.put("inboundPath", "/api/v1/channels/" + c.getToken() + "/message");
        m.put("outboundUrl", c.getOutboundUrl());
        m.put("secretConfigured", c.getSecret() != null && !c.getSecret().isBlank());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private Long serviceUser(AgentRegistry agent) {
        String username = "channel_agent_" + agent.getId();
        SysUser user = userMapper.findByUsername(username);
        if (user != null) return user.getId();
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        u.setDisplayName("渠道服务账号");
        u.setIsPlatformAdmin(0);
        u.setIsServiceAccount(1);
        u.setTenantId(agent.getTenantId());
        u.setStatus("ENABLED");
        u.setFailedAttempts(0);
        u.setMfaEnabled(0);
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return u.getId();
    }

    private Conversation resolveConversation(AgentChannel channel, AgentRegistry agent, String sessionId,
                                             String externalUserId, Long serviceUserId) {
        Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getSessionId, sessionId).last("LIMIT 1"));
        if (existing != null) {
            if (!existing.getAgentId().equals(agent.getId())) {
                throw new BusinessException(409, "sessionId 已绑定其他 Agent");
            }
            return existing;
        }
        Conversation c = new Conversation();
        c.setSessionId(sessionId);
        c.setAgentId(agent.getId());
        c.setUserId(serviceUserId);
        c.setExternalUserId(externalUserId);
        c.setTitle(agent.getName() + "（" + channel.getName() + "）");
        c.setSource("channel");
        c.setStatus("ACTIVE");
        c.setTenantId(channel.getTenantId());
        c.setLastActiveAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        conversationMapper.insert(c);
        return c;
    }

    private void record(AgentChannel channel, String direction, String externalUserId, String sessionId,
                        String content, String status, String error) {
        try {
            ChannelMessage m = new ChannelMessage();
            m.setChannelId(channel.getId());
            m.setTenantId(channel.getTenantId());
            m.setDirection(direction);
            m.setExternalUserId(externalUserId);
            m.setSessionId(sessionId);
            m.setContent(content);
            m.setStatus(status);
            m.setErrorMessage(truncate(error, 1000));
            m.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(m);
        } catch (Exception e) {
            log.debug("渠道消息记录失败：{}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void sendOutbound(AgentChannel channel, String answer, String sessionId) throws Exception {
        String url = channel.getOutboundUrl();
        networkPolicy.validateHttpUrl(url, "出站 Webhook");
        String type = channel.getType() == null ? "WEBHOOK" : channel.getType().toUpperCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        String secret = channel.getSecret() == null ? null : cryptoService.decrypt(channel.getSecret());
        switch (type) {
            case "SLACK" -> body.put("text", answer);
            case "DINGTALK" -> {
                body.put("msgtype", "text");
                body.put("text", Map.of("content", answer));
                if (secret != null && !secret.isBlank()) {
                    long ts = System.currentTimeMillis();
                    String sign = base64Hmac(ts + "\n" + secret, secret);
                    url = appendQuery(url, "timestamp", String.valueOf(ts));
                    url = appendQuery(url, "sign", sign);
                }
            }
            case "FEISHU" -> {
                body.put("msg_type", "text");
                body.put("content", Map.of("text", answer));
                if (secret != null && !secret.isBlank()) {
                    long ts = System.currentTimeMillis() / 1000;
                    body.put("timestamp", String.valueOf(ts));
                    body.put("sign", base64Hmac(ts + "\n" + secret, secret));
                }
            }
            case "WECOM" -> {
                body.put("msgtype", "text");
                body.put("text", Map.of("content", answer));
            }
            default -> {
                body.put("answer", answer);
                body.put("sessionId", sessionId);
            }
        }
        String json = objectMapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException(502, "出站 Webhook HTTP " + response.statusCode());
        }
    }

    private String appendQuery(String url, String key, String value) {
        return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
    }

    private String base64Hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> payload) {
        // 通用
        for (String key : List.of("text", "content", "message")) {
            Object v = payload.get(key);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        // 飞书事件
        Map<String, Object> event = map(payload.get("event"));
        Map<String, Object> message = map(event.get("message"));
        if (message.get("content") != null) {
            String raw = String.valueOf(message.get("content"));
            try {
                Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
                if (parsed.get("text") != null) return String.valueOf(parsed.get("text"));
            } catch (Exception ignored) {
            }
            return raw;
        }
        if (event.get("text") != null) return String.valueOf(event.get("text"));
        // 钉钉/企微
        Map<String, Object> textObj = map(payload.get("text"));
        if (textObj.get("content") != null) return String.valueOf(textObj.get("content"));
        // Slack
        if (event.get("text") != null) return String.valueOf(event.get("text"));
        return null;
    }

    private String extractUserId(Map<String, Object> payload) {
        for (String key : List.of("userId", "user_id", "open_id", "senderId", "from")) {
            Object v = payload.get(key);
            if (v instanceof String s && !s.isBlank()) return s;
            if (v instanceof Map<?, ?> m && m.get("user_id") != null) return String.valueOf(m.get("user_id"));
        }
        Map<String, Object> event = map(payload.get("event"));
        Map<String, Object> sender = map(event.get("sender"));
        if (sender.get("sender_id") != null) {
            Object sid = sender.get("sender_id");
            if (sid instanceof Map<?, ?> m && m.get("open_id") != null) return String.valueOf(m.get("open_id"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String normalizeType(String type) {
        String t = type == null || type.isBlank() ? "WEBHOOK" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) throw new BusinessException(400, "不支持的渠道类型：" + type);
        return t;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
