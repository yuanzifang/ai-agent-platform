package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 知识库直传元数据校验策略：类型白名单、大小上限、安全文件名。 */
class KnowledgeUploadPolicyTest {

    private final KnowledgeUploadPolicy policy = new KnowledgeUploadPolicy();
    private static final int MAX = 20 * 1024 * 1024;

    @Test
    void rejectsOversizedAndUnsupportedType() {
        BusinessException tooLarge = assertThrows(BusinessException.class,
                () -> policy.validateMetadata("large.pdf", (long) MAX + 1, MAX));
        assertEquals(413, tooLarge.getCode());

        BusinessException badType = assertThrows(BusinessException.class,
                () -> policy.validateMetadata("payload.exe", 3L, MAX));
        assertEquals(415, badType.getCode());

        BusinessException noExt = assertThrows(BusinessException.class,
                () -> policy.validateMetadata("README", 3L, MAX));
        assertEquals(415, noExt.getCode());
    }

    @Test
    void allowsKnownTypesRegardlessOfSizeNullAndCase() {
        policy.validateMetadata("doc.PDF", null, MAX);
        policy.validateMetadata("表格.xlsx", (long) MAX, MAX);
        policy.validateMetadata("notes.md", 1L, MAX);
    }

    @Test
    void safeFileNameStripsPathSeparatorsAndControlChars() {
        assertEquals("a_b.txt", policy.safeFileName("a/b.txt"));
        assertEquals("a_b.txt", policy.safeFileName("a\\b.txt"));
        assertEquals("a__b.txt", policy.safeFileName("a\r\nb.txt"));
    }

    @Test
    void safeFileNameFallsBackAndKeepsExtensionOnTruncation() {
        assertEquals("file", policy.safeFileName(null));
        assertEquals("file", policy.safeFileName("   "));
        String longName = "x".repeat(200) + ".pdf";
        String safe = policy.safeFileName(longName);
        assertEquals(128, safe.length());
        // 保留尾部，扩展名必须存活
        assertEquals(".pdf", safe.substring(safe.length() - 4));
    }
}
