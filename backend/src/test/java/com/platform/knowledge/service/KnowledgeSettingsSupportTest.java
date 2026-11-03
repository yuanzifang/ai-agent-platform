package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识库检索参数校验与文本工具拆分后的行为回归。 */
class KnowledgeSettingsSupportTest {

    @Test
    void normalizesBackendsAndRejectsUnknown() {
        assertEquals("ES", KnowledgeSettingsSupport.normalizeBackend(" es ", "MYSQL", Set.of("MYSQL", "ES")));
        assertEquals("MYSQL", KnowledgeSettingsSupport.normalizeBackend(" ", "mysql", Set.of("MYSQL", "ES")));
        assertThrows(BusinessException.class,
                () -> KnowledgeSettingsSupport.normalizeBackend("QDRANT", "MYSQL", Set.of("MYSQL", "ES")));
    }

    @Test
    void normalizesRetrievalSettingsWithinBounds() {
        assertEquals("hybrid", KnowledgeSettingsSupport.normalizeRetrievalMode(null));
        assertEquals("vector", KnowledgeSettingsSupport.normalizeRetrievalMode(" VECTOR "));
        assertThrows(BusinessException.class,
                () -> KnowledgeSettingsSupport.normalizeRetrievalMode("unknown"));
        assertEquals(0.5, KnowledgeSettingsSupport.normalizeScoreThreshold(null, 0.5));
        assertEquals(1.0, KnowledgeSettingsSupport.normalizeScoreThreshold(2.0, 0.5));
        assertEquals(0.0, KnowledgeSettingsSupport.normalizeScoreThreshold(-1.0, 0.5));
        assertEquals(0.35, KnowledgeSettingsSupport.normalizeKeywordWeight(null, 0.35));
        assertEquals(300, KnowledgeSettingsSupport.normalizeCacheTtl(null));
        assertEquals(86400, KnowledgeSettingsSupport.normalizeCacheTtl(99999));
        assertEquals(2000, KnowledgeSettingsSupport.normalizeCacheMaxEntries(null));
        assertArrayEquals(new int[]{5, 20}, KnowledgeSettingsSupport.normalizeTopK(null, null));
        assertArrayEquals(new int[]{50, 50}, KnowledgeSettingsSupport.normalizeTopK(99, 99));
    }

    @Test
    void serializesUserIdsAndTruncatesAtSentenceBoundary() {
        assertEquals("[\"1\",\"2\"]", KnowledgeSettingsSupport.usersJson(List.of(1L, 2L)));
        assertEquals("你好。…", KnowledgeSettingsSupport.truncateAtSentence("你好。后面还有很长的内容", 3));
        assertTrue(KnowledgeSettingsSupport.truncateAtSentence("abcdefghijklmnopqrstuvwxyz", 10).endsWith("…"));
    }
}
