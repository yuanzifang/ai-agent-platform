package com.platform.conversation.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.service.QuotaService;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.mapper.ChatAttachmentMapper;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.mapper.ConversationShareMapper;
import com.platform.conversation.mapper.FeedbackMapper;
import com.platform.conversation.mapper.MessageMapper;
import com.platform.eval.service.EvalService;
import com.platform.knowledge.service.StorageService;
import com.platform.security.JwtPrincipal;
import com.platform.system.mapper.AgentUserGrantMapper;
import com.platform.system.service.AuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 新成员没有会话时，list 不应生成 IN () 或调用空批次查询。 */
class ConversationServiceListTest {

    @Test
    void emptyConversationPageReturnsWithoutBatchLookup() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        AgentRegistryMapper agentMapper = mock(AgentRegistryMapper.class);
        Page<Conversation> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(conversationMapper.selectPage(any(Page.class), any())).thenReturn(page);
        ConversationService service = new ConversationService(conversationMapper,
                mock(MessageMapper.class), mock(ChatAttachmentMapper.class), mock(QuotaService.class),
                mock(FeedbackMapper.class), mock(ConversationShareMapper.class), agentMapper,
                mock(StorageService.class), mock(AuditService.class), mock(AgentUserGrantMapper.class),
                mock(EvalService.class), mock(com.platform.agent.service.CaseService.class), new ObjectMapper());

        Map<String, Object> result = service.list(null, 1, 20,
                new JwtPrincipal(7L, 1L, false, List.of("MEMBER"), List.of()));

        assertEquals(0L, result.get("total"));
        assertEquals(List.of(), result.get("list"));
        verify(agentMapper, never()).selectBatchIds(any());
    }
}
