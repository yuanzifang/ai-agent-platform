package com.platform.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.service.QuotaService;
import com.platform.common.exception.BusinessException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P0：会话附件类型和 12MB 大小边界。 */
class ConversationServiceAttachmentTest {

    @Test
    void rejectsOversizedAttachment() {
        Fixture f = fixture();
        ConversationService.MultipartFilePart file = new ConversationService.MultipartFilePart(
                "large.pdf", "application/pdf", new byte[12 * 1024 * 1024 + 1]);
        BusinessException error = assertThrows(BusinessException.class,
                () -> f.service.saveAttachment(1L, file, principal()));
        assertEquals(413, error.getCode());
    }

    @Test
    void rejectsExecutableOrUnknownType() {
        Fixture f = fixture();
        ConversationService.MultipartFilePart file = new ConversationService.MultipartFilePart(
                "payload.exe", "application/x-msdownload", new byte[]{1, 2, 3});
        BusinessException error = assertThrows(BusinessException.class,
                () -> f.service.saveAttachment(1L, file, principal()));
        assertEquals(415, error.getCode());
    }

    @Test
    void rejectsExecutableEvenWhenMimeIsSpoofedAsPdf() {
        Fixture f = fixture();
        ConversationService.MultipartFilePart file = new ConversationService.MultipartFilePart(
                "payload.exe", "application/pdf", new byte[]{1, 2, 3});
        BusinessException error = assertThrows(BusinessException.class,
                () -> f.service.saveAttachment(1L, file, principal()));
        assertEquals(415, error.getCode());
    }

    @Test
    void allowsKnownFileEvenWhenMimeIsOctetStream() {
        Fixture f = fixture();
        ConversationService.MultipartFilePart file = new ConversationService.MultipartFilePart(
                "report.pdf", "application/octet-stream", new byte[]{1, 2, 3});
        // 只验证校验阶段放行；后续存储/DB 由其他测试覆盖。
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> f.service.saveAttachment(1L, file, principal()));
    }

    private Fixture fixture() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setUserId(7L);
        conversation.setStatus("ACTIVE");
        when(conversationMapper.selectById(1L)).thenReturn(conversation);
        ChatAttachmentMapper attachmentMapper = mock(ChatAttachmentMapper.class);
        when(attachmentMapper.insert(org.mockito.ArgumentMatchers.any(com.platform.conversation.entity.ChatAttachment.class))).thenAnswer(inv -> {
            inv.getArgument(0, com.platform.conversation.entity.ChatAttachment.class).setId(3L);
            return 1;
        });
        ConversationService service = new ConversationService(conversationMapper,
                mock(MessageMapper.class), attachmentMapper, mock(QuotaService.class),
                mock(FeedbackMapper.class), mock(ConversationShareMapper.class), mock(AgentRegistryMapper.class),
                mock(StorageService.class), mock(AuditService.class), mock(AgentUserGrantMapper.class),
                mock(EvalService.class), mock(com.platform.agent.service.CaseService.class), new ObjectMapper());
        return new Fixture(service);
    }

    private JwtPrincipal principal() {
        return new JwtPrincipal(7L, 1L, false, List.of("MEMBER"), List.of());
    }

    private record Fixture(ConversationService service) {
    }
}
