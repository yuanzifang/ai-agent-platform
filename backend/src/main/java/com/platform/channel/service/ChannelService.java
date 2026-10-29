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
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final com.platform.system.service.NotificationService notificationService;
    private final StringRedisTemplate redis;
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

    public Map<String, Object> handleInbound(String token, String rawBody,
                                             Map<String, String> headers, Map<String, String> params) {
        AgentChannel channel = channelMapper.findByTokenIgnoreTenant(token);
        if (channel == null) throw new BusinessException(404, "渠道不存在");
        if (!"ENABLED".equals(channel.getStatus())) throw new BusinessException(403, "渠道已停用");
        Map<String, Object> config = config(channel);
        checkRateLimit(channel, config);
        String type = channel.getType() == null ? "WEBHOOK" : channel.getType().toUpperCase(Locale.ROOT);
        Map<String, Object> payload = switch (type) {
            case "FEISHU" -> feishuPayload(rawBody, headers, config);
            case "WECOM" -> wecomPayload(rawBody, params, config);
            default -> parseJson(rawBody);
        };
        if ("url_verification".equals(str(payload.get("type"))) && payload.get("challenge") != null) {
            return Map.of("challenge", payload.get("challenge"));
        }
        String eventId = extractEventId(payload);
        if (eventId != null && !claimEvent(channel, eventId)) {
            return "WECOM".equals(type) ? Map.of("_wecomPlain", "success") : Map.of("duplicate", true);
        }
        String text = extractText(payload);
        if (text == null || text.isBlank()) {
            String mediaUrl = extractMediaUrl(payload);
            if (mediaUrl != null && !mediaUrl.isBlank()) text = "[媒体消息] " + mediaUrl;
        }
        if (text == null || text.isBlank()) throw new BusinessException(400, "未识别到消息文本");
        final String messageText = text;
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
            record(channel, "IN", externalUserId, sessionId, truncate(messageText, 8000), "SUCCESS", null);
            Object result;
            try {
                result = chatStreamService.sendWithSource(conversation.getId(),
                        new ChatStreamService.SendRequest(messageText, false, Map.of(), null), principal, "channel");
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
                    sendOutboundWithRetry(channel, answer == null ? "" : answer, sessionId);
                    outboundSent = true;
                } catch (Exception e) {
                    outboundError = rootMessage(e);
                    log.warn("渠道出站失败 channel={}：{}", channel.getId(), outboundError);
                    notifyOutboundFailure(channel, outboundError);
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
            if ("WECOM".equals(type)) {
                String xmlReply = wecomReply(payload, config, params, answer == null ? "" : answer);
                if (xmlReply != null) r.put("_wecomXml", xmlReply);
            }
            return r;
        });
    }

    /** 企业微信 URL 验证：返回解密后的 echostr 明文。 */
    public String wecomVerify(String token, Map<String, String> params) {
        AgentChannel channel = channelMapper.findByTokenIgnoreTenant(token);
        if (channel == null || !"WECOM".equalsIgnoreCase(channel.getType())) {
            throw new BusinessException(404, "企微渠道不存在");
        }
        Map<String, Object> config = config(channel);
        String echostr = params.get("echostr");
        String aesKey = str(config.get("encodingAesKey"));
        if (echostr == null || echostr.isBlank()) return "ok";
        if (aesKey == null || aesKey.isBlank()) return "ok";
        return wecomDecrypt(echostr, aesKey);
    }

    // ---------- 渠道签名/解密 ----------

    private Map<String, Object> feishuPayload(String rawBody, Map<String, String> headers,
                                              Map<String, Object> config) {
        String body = rawBody == null ? "" : rawBody;
        String encryptKey = str(config.get("encryptKey"));
        if (encryptKey != null && !encryptKey.isBlank()) {
            String ts = header(headers, "x-lark-request-timestamp");
            String nonce = header(headers, "x-lark-request-nonce");
            String sig = header(headers, "x-lark-signature");
            if (ts == null || nonce == null || sig == null) {
                throw new BusinessException(400, "飞书事件缺少签名头");
            }
            String expected = sha256Hex(ts + nonce + encryptKey + body);
            if (!expected.equalsIgnoreCase(sig)) throw new BusinessException(403, "飞书签名校验失败");
        }
        Map<String, Object> payload = parseJson(body);
        if (payload.get("encrypt") != null && encryptKey != null && !encryptKey.isBlank()) {
            payload = parseJson(feishuDecrypt(String.valueOf(payload.get("encrypt")), encryptKey));
        }
        return payload;
    }

    private Map<String, Object> wecomPayload(String rawBody, Map<String, String> params,
                                             Map<String, Object> config) {
        Map<String, Object> body = parseXml(rawBody);
        String encrypt = str(body.get("Encrypt"));
        if (encrypt == null || encrypt.isBlank()) encrypt = params.get("encrypt");
        String token = str(config.get("verificationToken"));
        if (token != null && !token.isBlank() && encrypt != null && !encrypt.isBlank()) {
            String expected = wecomSignature(token, params.get("timestamp"), params.get("nonce"), encrypt);
            String actual = params.get("msg_signature");
            if (actual != null && !actual.equalsIgnoreCase(expected)) {
                throw new BusinessException(403, "企微签名校验失败");
            }
        }
        String aesKey = str(config.get("encodingAesKey"));
        if (encrypt != null && !encrypt.isBlank() && aesKey != null && !aesKey.isBlank()) {
            return parseXml(wecomDecrypt(encrypt, aesKey));
        }
        return body;
    }

    private String feishuDecrypt(String encrypt, String encryptKey) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] all = Base64.getMimeDecoder().decode(encrypt);
            byte[] iv = new byte[16];
            byte[] cipher = new byte[all.length - 16];
            System.arraycopy(all, 0, iv, 0, 16);
            System.arraycopy(all, 16, cipher, 0, cipher.length);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(cipher), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(400, "飞书事件解密失败");
        }
    }

    private String wecomDecrypt(String encrypt, String encodingAesKey) {
        try {
            byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
            byte[] iv = new byte[16];
            System.arraycopy(key, 0, iv, 0, 16);
            byte[] cipher = Base64.getMimeDecoder().decode(encrypt);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = c.doFinal(cipher);
            int len = ((plain[16] & 0xff) << 24) | ((plain[17] & 0xff) << 16)
                    | ((plain[18] & 0xff) << 8) | (plain[19] & 0xff);
            return new String(plain, 20, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(400, "企微消息解密失败");
        }
    }

    private String wecomSignature(String token, String timestamp, String nonce, String encrypt) {
        List<String> parts = new ArrayList<>(List.of(token,
                timestamp == null ? "" : timestamp, nonce == null ? "" : nonce, encrypt));
        parts.sort(String::compareTo);
        return sha1Hex(String.join("", parts));
    }

    private String sha256Hex(String input) {
        return digestHex("SHA-256", input);
    }

    private String sha1Hex(String input) {
        return digestHex("SHA-1", input);
    }

    private String digestHex(String algorithm, String input) {
        try {
            byte[] hash = MessageDigest.getInstance(algorithm).digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(500, "签名计算失败");
        }
    }

    private String wecomReply(Map<String, Object> payload, Map<String, Object> config,
                              Map<String, String> params, String answer) {
        String aesKey = str(config.get("encodingAesKey"));
        String token = str(config.get("verificationToken"));
        String ts = params == null ? null : params.get("timestamp");
        String nonce = params == null ? null : params.get("nonce");
        if (aesKey == null || aesKey.isBlank() || ts == null || nonce == null) return null;
        String from = cdata(str(payload.get("FromUserName")));
        String to = cdata(str(payload.get("ToUserName")));
        String content = cdata(answer);
        String xml = "<xml><ToUserName><![CDATA[" + from + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + to + "]]></FromUserName>"
                + "<CreateTime>" + (System.currentTimeMillis() / 1000) + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content + "]]></Content></xml>";
        try {
            String encrypt = wecomEncrypt(xml, aesKey, str(config.getOrDefault("corpId", "")));
            String signature = wecomSignature(token == null ? "" : token, ts, nonce, encrypt);
            return "<xml><Encrypt><![CDATA[" + encrypt + "]]></Encrypt>"
                    + "<MsgSignature><![CDATA[" + signature + "]]></MsgSignature>"
                    + "<TimeStamp>" + ts + "</TimeStamp>"
                    + "<Nonce><![CDATA[" + nonce + "]]></Nonce></xml>";
        } catch (Exception e) {
            log.warn("企微加密回复失败：{}", e.getMessage());
            return null;
        }
    }

    private String wecomEncrypt(String plainXml, String encodingAesKey, String corpId) throws Exception {
        byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
        byte[] iv = new byte[16];
        System.arraycopy(key, 0, iv, 0, 16);
        byte[] msg = plainXml.getBytes(StandardCharsets.UTF_8);
        byte[] appId = new byte[16];
        byte[] corp = corpId == null ? new byte[0] : corpId.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(corp, 0, appId, 0, Math.min(corp.length, 16));
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        byte[] raw = new byte[16 + 4 + msg.length + 16];
        System.arraycopy(random, 0, raw, 0, 16);
        raw[16] = (byte) (msg.length >> 24);
        raw[17] = (byte) (msg.length >> 16);
        raw[18] = (byte) (msg.length >> 8);
        raw[19] = (byte) msg.length;
        System.arraycopy(msg, 0, raw, 20, msg.length);
        System.arraycopy(appId, 0, raw, 20 + msg.length, 16);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(c.doFinal(raw));
    }

    private String cdata(String text) {
        if (text == null) return "";
        return text.replace("]]>", "]]&gt;");
    }

    private String extractEventId(Map<String, Object> payload) {
        Object header = payload.get("header");
        if (header instanceof Map<?, ?> h && h.get("event_id") != null) return String.valueOf(h.get("event_id"));
        for (String key : List.of("MsgId", "MsgID", "event_id", "eventId")) {
            if (payload.get(key) != null) return String.valueOf(payload.get(key));
        }
        Object event = payload.get("event");
        if (event instanceof Map<?, ?> e && e.get("event_id") != null) return String.valueOf(e.get("event_id"));
        return null;
    }

    private boolean claimEvent(AgentChannel channel, String eventId) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    "channel:event:" + channel.getId() + ":" + eventId, "1", Duration.ofMinutes(10));
            return !Boolean.FALSE.equals(ok);
        } catch (Exception e) {
            return true;
        }
    }

    private Map<String, Object> parseXml(String xml) {
        if (xml == null || xml.isBlank()) return Map.of();
        try {
            var doc = Jsoup.parse(xml, "", Parser.xmlParser());
            Map<String, Object> map = new LinkedHashMap<>();
            for (var child : doc.children()) {
                for (var node : child.children()) {
                    map.put(node.tagName(), node.text());
                }
            }
            if (map.isEmpty()) {
                for (var node : doc.children()) map.put(node.tagName(), node.text());
            }
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return body == null || body.isBlank() ? Map.of()
                    : objectMapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "消息体不是合法 JSON");
        }
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(AgentChannel channel) {
        if (channel.getConfigJson() == null || channel.getConfigJson().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(channel.getConfigJson(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void checkRateLimit(AgentChannel channel, Map<String, Object> config) {
        int limit = intValue(config.get("rateLimitPerMinute"), 60);
        if (limit <= 0) return;
        try {
            long minute = System.currentTimeMillis() / 60000;
            String key = "channel:rate:" + channel.getId() + ":" + minute;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, Duration.ofMinutes(2));
            if (count != null && count > limit) {
                throw new BusinessException(429, "渠道调用过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.debug("渠道限流不可用：{}", e.getMessage());
        }
    }

    private int intValue(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
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
            } catch (Exception e) {
                log.warn("渠道配置/消息解析失败：{}", e.getMessage());
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

    private void sendOutboundWithRetry(AgentChannel channel, String answer, String sessionId) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                sendOutbound(channel, answer, sessionId);
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    try { Thread.sleep(250L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("outbound failed") : last;
    }

    private void notifyOutboundFailure(AgentChannel channel, String error) {
        try {
            List<Long> recipients = channel.getCreatedBy() == null ? List.of() : List.of(channel.getCreatedBy());
            notificationService.notifyTemplate(channel.getTenantId(), recipients, "channel_error",
                    "/agent/edit/" + channel.getAgentId(),
                    "notification.channel.error.title", "notification.channel.error.content",
                    channel.getName(), truncate(error, 500));
        } catch (Exception e) {
            log.debug("渠道失败通知写入失败：{}", e.getMessage());
        }
    }

    /** 从常见 Webhook/飞书/企微/钉钉 payload 中提取媒体 URL（仅兜底路由，不下载媒体）。 */
    private String extractMediaUrl(Object payload) {
        if (payload == null) return null;
        if (payload instanceof Map<?, ?> map) {
            for (String key : List.of("image_url", "imageUrl", "pic_url", "picUrl",
                    "media_url", "mediaUrl", "file_url", "fileUrl", "url")) {
                Object v = map.get(key);
                if (v instanceof String s && s.startsWith("http")) return s;
                if (v instanceof Map<?, ?> nested && nested.get("url") instanceof String s && s.startsWith("http")) return s;
            }
            for (Object value : map.values()) {
                String found = extractMediaUrl(value);
                if (found != null) return found;
            }
        } else if (payload instanceof List<?> list) {
            for (Object value : list) {
                String found = extractMediaUrl(value);
                if (found != null) return found;
            }
        }
        return null;
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
        // 企微 XML
        if (payload.get("Content") != null) return String.valueOf(payload.get("Content"));
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
            } catch (Exception e) {
                log.warn("渠道配置/消息解析失败：{}", e.getMessage());
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
        if (payload.get("FromUserName") != null) return String.valueOf(payload.get("FromUserName"));
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
