package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeishuDocumentServiceTest {

    private final FeishuDocumentService service = new FeishuDocumentService(new ObjectMapper());

    @Test
    void rejectsNonFeishuHostBeforeAnyApiCall() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.collect("https://example.com/docx/abc", "app", "secret"));
        assertEquals(400, error.getCode());
    }

    @Test
    void rejectsHttpBeforeAnyApiCall() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.collect("http://team.feishu.cn/docx/abc", "app", "secret"));
        assertEquals(400, error.getCode());
    }

    @Test
    void rejectsUnsupportedFeishuPathBeforeTokenExchange() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.collect("https://team.feishu.cn/slides/abc", "app", "secret"));
        assertEquals(400, error.getCode());
        assertEquals("无法识别飞书链接类型，请使用 /docx/、/wiki/ 或 /drive/folder/ 链接", error.getMessage());
    }

    @Test
    void rejectsMissingCredentials() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.collect("https://team.feishu.cn/docx/abc", "app", " "));
        assertEquals(400, error.getCode());
    }
}
