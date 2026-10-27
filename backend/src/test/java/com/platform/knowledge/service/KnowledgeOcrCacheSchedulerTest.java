package com.platform.knowledge.service;

import com.platform.knowledge.mapper.KnowledgeOcrCacheMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeOcrCacheSchedulerTest {

    @Test
    void cleansOcrCacheOlderThanRetention() {
        KnowledgeOcrCacheMapper mapper = mock(KnowledgeOcrCacheMapper.class);
        when(mapper.deleteOlderThan(any(), eq(10000))).thenReturn(3);
        KnowledgeOcrCacheScheduler scheduler = new KnowledgeOcrCacheScheduler(mapper);

        scheduler.cleanup();

        verify(mapper).deleteOlderThan(any(), eq(10000));
    }
}
