package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.auth.mapper.UserSessionMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentTraceMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.AuditLogin;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.AuditLoginMapper;
import com.platform.conversation.mapper.MessageMapper;
import com.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账号合规（需求 3.25(7)）：个人数据导出 + 注销。
 * 注销前校验资源归属（名下 Agent/知识库、租户最后 OWNER），通过后匿名化并禁用账号；消息/Trace/审计保留（合规）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final SysUserMapper userMapper;
    private final UserSessionMapper sessionMapper;
    private final AgentRegistryMapper agentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AgentTraceMapper traceMapper;
    private final AuditLoginMapper auditLoginMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 导出当前用户个人数据（资料/会话与消息/Trace/登录记录），JSON 下载。 */
    public byte[] exportData(JwtPrincipal principal) {
        SysUser user = userMapper.findByIdIgnore(principal.userId());
        if (user == null) throw new BusinessException(404, "用户不存在");
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("username", user.getUsername());
        profile.put("displayName", user.getDisplayName());
        profile.put("tenantId", user.getTenantId());
        profile.put("createdAt", user.getCreatedAt());
        data.put("profile", profile);

        List<Conversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, principal.userId())
                        .orderByDesc(Conversation::getId));
        List<Map<String, Object>> convs = new ArrayList<>();
        for (Conversation c : conversations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("sessionId", c.getSessionId());
            m.put("agentId", c.getAgentId());
            m.put("title", c.getTitle());
            m.put("source", c.getSource());
            m.put("createdAt", c.getCreatedAt());
            m.put("messages", messageMapper.selectList(new LambdaQueryWrapper<com.platform.conversation.entity.Message>()
                    .eq(com.platform.conversation.entity.Message::getConversationId, c.getId())
                    .orderByAsc(com.platform.conversation.entity.Message::getId)
                    .select(com.platform.conversation.entity.Message::getId,
                            com.platform.conversation.entity.Message::getRole,
                            com.platform.conversation.entity.Message::getContent,
                            com.platform.conversation.entity.Message::getStatus,
                            com.platform.conversation.entity.Message::getCreatedAt)));
            convs.add(m);
        }
        data.put("conversations", convs);

        data.put("traces", traceMapper.selectList(new LambdaQueryWrapper<AgentTrace>()
                .eq(AgentTrace::getUserId, principal.userId())
                .orderByDesc(AgentTrace::getId)
                .last("LIMIT 1000")));
        data.put("loginRecords", auditLoginMapper.selectList(new LambdaQueryWrapper<AuditLogin>()
                .eq(AuditLogin::getUserId, principal.userId())
                .orderByDesc(AuditLogin::getId)
                .last("LIMIT 500")));
        data.put("exportedAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        } catch (Exception e) {
            throw new BusinessException(500, "导出失败");
        }
    }

    /** 注销当前账号：校验密码/资源归属，匿名化并踢出会话。 */
    @Transactional
    public void deleteAccount(JwtPrincipal principal, String password, String confirmText) {
        SysUser user = userMapper.findByIdIgnore(principal.userId());
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(400, "密码错误");
        }
        if (!"注销".equals(confirmText)) {
            throw new BusinessException(400, "请输入“注销”确认");
        }
        long agents = agentMapper.selectCount(new LambdaQueryWrapper<AgentRegistry>()
                .eq(AgentRegistry::getOwnerId, principal.userId())
                .ne(AgentRegistry::getStatus, "DELETED"));
        if (agents > 0) throw new BusinessException(400, "名下仍有 " + agents + " 个 Agent，请先转移或删除后再注销");
        long kbs = kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getCreatedBy, principal.userId()));
        if (kbs > 0) throw new BusinessException(400, "名下仍有 " + kbs + " 个知识库，请先转移或删除后再注销");
        if (user.getTenantId() != null) {
            List<String> roles = userMapper.findRoleKeys(principal.userId());
            if (roles.contains("OWNER") && userMapper.countOtherOwners(user.getTenantId(), principal.userId()) == 0) {
                throw new BusinessException(400, "你是租户唯一所有者，请先转让所有者后再注销");
            }
        }
        String anonymized = "deleted_" + user.getId() + "_" + UUID.randomUUID().toString().substring(0, 8);
        userMapper.anonymize(user.getId(), anonymized, passwordEncoder.encode(UUID.randomUUID().toString()));
        sessionMapper.revokeAllByUser(user.getId());
        auditService.record(principal.userId(), "user.account.delete", "user",
                String.valueOf(user.getId()), "SUCCESS", Map.of("username", user.getUsername()));
    }
}
