package com.platform.workflow.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 检索配置/节点模板：拆分后保持原有默认值与重排字段语义。 */
class WorkflowRetrievalOptionsTest {

    @Test
    void buildsRerankConfig() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rerank", true);
        node.put("rerankTopN", 7);

        Map<String, Object> config = WorkflowRetrievalOptions.from(node, "hybrid");

        assertEquals("hybrid", config.get("mode"));
        assertEquals(true, config.get("rerank"));
        assertEquals(7, config.get("rerankTopN"));
    }

    @Test
    void ignoresBlankModeAndNonPositiveRerankTopN() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rerank", false);
        node.put("rerankTopN", 0);

        Map<String, Object> config = WorkflowRetrievalOptions.from(node, " ");

        assertFalse(config.containsKey("mode"));
        assertEquals(false, config.get("rerank"));
        assertFalse(config.containsKey("rerankTopN"));
    }

    @Test
    void knowledgeNodeKeepsFrontendTemplateDefaults() {
        Map<String, Object> node = WorkflowRetrievalOptions.knowledgeNode("kb1", "llm1");

        assertEquals("kb1", node.get("id"));
        assertEquals("KNOWLEDGE", node.get("type"));
        assertEquals("知识库检索", node.get("name"));
        assertEquals("hybrid", node.get("mode"));
        assertEquals(5, node.get("topK"));
        assertEquals(true, node.get("rerank"));
        assertEquals(5, node.get("rerankTopN"));
        assertEquals("{{input}}", node.get("queryTemplate"));
        assertEquals("llm1", node.get("next"));
        assertTrue(node.containsKey("rerank"));
    }
}
