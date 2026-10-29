package com.platform.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nConfigTest {

    private final I18nConfig config = new I18nConfig();

    @Test
    void resolvesLanguageFromAcceptHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US,en;q=0.9");

        Locale locale = config.localeResolver().resolveLocale(request);

        assertEquals("en", locale.getLanguage());
    }

    @Test
    void defaultsToSimplifiedChinese() {
        assertEquals(Locale.SIMPLIFIED_CHINESE,
                config.localeResolver().resolveLocale(new MockHttpServletRequest()));
    }

    @Test
    void loadsBackendBundles() {
        assertEquals("Name is required",
                config.messageSource().getMessage("error.required", new Object[]{"Name"}, null, Locale.US));
        assertEquals("名称不能为空",
                config.messageSource().getMessage("error.required", new Object[]{"名称"}, null,
                        Locale.SIMPLIFIED_CHINESE));
    }
}
