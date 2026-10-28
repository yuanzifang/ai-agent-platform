package com.platform.common.checkpoint;

import com.platform.conversation.mapper.ChatCheckpointMapper;
import com.platform.workflow.mapper.WorkflowCheckpointMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointRetentionSchedulerTest {

    @Test
    void deletesBothCheckpointTypes() {
        WorkflowCheckpointMapper workflow = mock(WorkflowCheckpointMapper.class);
        ChatCheckpointMapper chat = mock(ChatCheckpointMapper.class);
        when(workflow.deleteOldKeepLatest(any(LocalDateTime.class), anyInt())).thenReturn(2);
        when(chat.deleteOld(any(LocalDateTime.class), anyInt())).thenReturn(1);

        CheckpointRetentionScheduler scheduler = new CheckpointRetentionScheduler(workflow, chat);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 30);
        ReflectionTestUtils.setField(scheduler, "batch", 100);
        scheduler.cleanup();

        verify(workflow).deleteOldKeepLatest(any(LocalDateTime.class), anyInt());
        verify(chat).deleteOld(any(LocalDateTime.class), anyInt());
    }
}
