package com.platform.common.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendMessageTranslatorTest {

    private final BackendMessageTranslator translator = new BackendMessageTranslator(messageSource());

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void language(String lang) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", lang);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void keepsChineseByDefault() {
        assertEquals("名称不能为空", translator.translate("名称不能为空"));
    }

    @Test
    void translatesExactEnglish() {
        language("en-US,en;q=0.9");
        assertEquals("Invalid username or password", translator.translate("用户名或密码错误"));
        assertEquals("Permission denied", translator.translate("无操作权限（权限点校验未通过）"));
        assertEquals("System busy; please try again later",
                translator.translate("系统繁忙，请稍后重试"));
        assertEquals("Account does not belong to this tenant",
                translator.translate("账号不属于该租户"));
        assertEquals("Invalid MFA code", translator.translate("MFA 验证码错误"));
        assertEquals("Import content is empty", translator.translate("导入内容为空"));
    }

    @Test
    void translatesPatternsAndNouns() {
        language("en");
        assertEquals("Knowledge base name is required", translator.translate("知识库名称不能为空"));
        assertEquals("Document not found: 42", translator.translate("文档不存在：42"));
        assertEquals("User already exists: dev", translator.translate("用户已存在：dev"));
        assertEquals("Case format is invalid", translator.translate("用例格式错误"));
        assertEquals("Workflow must not exceed 30", translator.translate("工作流不能超过30个节点"));
    }

    @Test
    void leavesUnknownMessagesUnchanged() {
        language("en-US");
        assertEquals("some very specific internal message", translator.translate("some very specific internal message"));
    }
}
