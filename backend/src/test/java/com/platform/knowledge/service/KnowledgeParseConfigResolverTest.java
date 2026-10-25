package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.system.mapper.SysConfigMapper;
import com.platform.system.mapper.SysTenantConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class KnowledgeParseConfigResolverTest {

    @Test
    void decryptsDocumentPdfPassword() throws Exception {
        CryptoService crypto = new CryptoService("test-aes-key", "test-jwt-secret");
        KnowledgeParseConfigResolver resolver = new KnowledgeParseConfigResolver(
                mock(SysConfigMapper.class), mock(SysTenantConfigMapper.class),
                new ObjectMapper(), crypto);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(9L);

        String encrypted = crypto.encrypt("user-password");
        ParseOptions options = resolver.resolve(kb,
                new ObjectMapper().writeValueAsString(Map.of("pdfPassword", encrypted)));

        assertEquals("user-password", options.pdfPassword());
    }

    @Test
    void rejectsCorruptedDocumentPdfPassword() {
        CryptoService crypto = new CryptoService("test-aes-key", "test-jwt-secret");
        KnowledgeParseConfigResolver resolver = new KnowledgeParseConfigResolver(
                mock(SysConfigMapper.class), mock(SysTenantConfigMapper.class),
                new ObjectMapper(), crypto);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(9L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.resolve(kb, "{\"pdfPassword\":\"v1:not-base64\"}"));
        assertEquals(422, error.getCode());
    }
}
