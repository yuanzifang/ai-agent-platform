package com.platform.eval.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvalStepCatalogTest {

    @Test
    void exposesAllStepTypes() {
        assertEquals(4, EvalStepCatalog.definitions().size());
    }

    @Test
    void validatesRetrievalAndRerankPayloads() {
        assertNull(EvalStepCatalog.validate("RETRIEVAL",
                "{\"chunk_ids\":[1,2]}", "{\"knowledgeBaseId\":1}"));
        assertNotNull(EvalStepCatalog.validate("RETRIEVAL",
                "{\"chunk_ids\":[1]}", "{}"));
        assertNull(EvalStepCatalog.validate("RERANK",
                "{\"ordered_chunk_ids\":[2,1]}", "{\"knowledgeBaseId\":1}"));
        assertNotNull(EvalStepCatalog.validate("RERANK",
                "{}", "{\"knowledgeBaseId\":1}"));
    }

    @Test
    void validatesRewriteAndAnswer() {
        assertNull(EvalStepCatalog.validate("REWRITE",
                "{\"must_keep\":[\"退款\"]}", null));
        assertNotNull(EvalStepCatalog.validate("REWRITE", "{}", null));
        assertNull(EvalStepCatalog.validate("ANSWER", null, null));
    }

    @Test
    void rejectsBrokenJson() {
        assertNotNull(EvalStepCatalog.validate("RETRIEVAL", "{", "{\"knowledgeBaseId\":1}"));
    }
}
