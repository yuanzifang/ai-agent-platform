package com.platform.conversation.service;

import com.platform.agent.service.ContextWindowService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatStreamRagCitationTest {

    @Test
    void trimsCitationsForDroppedRagFragments() {
        List<Map<String, Object>> citations = new ArrayList<>(List.of(
                Map.of("chunkId", 1), Map.of("chunkId", 2), Map.of("chunkId", 3),
                Map.of("chunkId", 4), Map.of("chunkId", 5)));
        ContextWindowService.Fitted fitted = new ContextWindowService.Fitted(
                "prompt", List.of(), true, 0, 0, true, 3, 8);

        ChatStreamService.trimUnusedRagCitations(citations, fitted);

        assertEquals(3, citations.size());
        assertEquals(1, citations.get(0).get("chunkId"));
        assertEquals(3, citations.get(2).get("chunkId"));
    }

    @Test
    void keepsCitationsWhenRagNotTruncated() {
        List<Map<String, Object>> citations = new ArrayList<>(List.of(Map.of("chunkId", 1)));
        ContextWindowService.Fitted fitted = new ContextWindowService.Fitted(
                "prompt", List.of(), false, 0, 0, false, 1, 1);

        ChatStreamService.trimUnusedRagCitations(citations, fitted);

        assertEquals(1, citations.size());
    }
}
