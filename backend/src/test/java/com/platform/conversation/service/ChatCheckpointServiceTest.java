package com.platform.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.conversation.entity.ChatCheckpoint;
import com.platform.conversation.mapper.ChatCheckpointMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCheckpointServiceTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void writesAndUpdatesCheckpoint() {
        ChatCheckpointMapper mapper = mock(ChatCheckpointMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        ChatCheckpointService service = new ChatCheckpointService(mapper, new ObjectMapper());
        TenantContext.setTenantId(1L);

        service.save(9L, "trace", "RUNNING", "partial", null, 2, 3,
                List.of(Map.of("content", "hit")));

        ArgumentCaptor<ChatCheckpoint> captor = ArgumentCaptor.forClass(ChatCheckpoint.class);
        verify(mapper).insert(captor.capture());
        ChatCheckpoint row = captor.getValue();
        assertEquals(9L, row.getConversationId());
        assertEquals(1L, row.getTenantId());
        assertEquals("RUNNING", row.getStatus());
        assertEquals("partial", row.getContent());
        assertEquals(2L, row.getInputTokens());
        assertEquals(3L, row.getOutputTokens());
    }

    @Test
    void runningStartClearsPreviousMessageAssociation() {
        ChatCheckpointMapper mapper = mock(ChatCheckpointMapper.class);
        ChatCheckpoint existing = new ChatCheckpoint();
        existing.setId(5L);
        existing.setConversationId(9L);
        existing.setTenantId(1L);
        existing.setMessageId(100L);
        when(mapper.selectOne(any())).thenReturn(existing);
        ChatCheckpointService service = new ChatCheckpointService(mapper, new ObjectMapper());

        service.save(1L, 9L, "trace-2", "RUNNING", "", null, 0, 0, null);

        ArgumentCaptor<ChatCheckpoint> captor = ArgumentCaptor.forClass(ChatCheckpoint.class);
        verify(mapper).updateById(captor.capture());
        assertNull(captor.getValue().getMessageId());
        assertNull(captor.getValue().getCitationsJson());
    }
}
