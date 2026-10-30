package com.platform.common.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 防止中英文资源包新增/删除 key 时漂移。 */
class I18nResourceParityTest {

    @Test
    void chineseAndEnglishBundlesHaveSameKeys() throws Exception {
        Properties zh = load("i18n/messages.properties");
        Properties en = load("i18n/messages_en.properties");
        Set<String> zhKeys = new TreeSet<>(zh.stringPropertyNames());
        Set<String> enKeys = new TreeSet<>(en.stringPropertyNames());
        assertEquals(zhKeys, enKeys, "中英文资源包 key 必须一致");
        zhKeys.forEach(key -> {
            assertFalse(zh.getProperty(key).isBlank(), "中文空值: " + key);
            assertFalse(en.getProperty(key).isBlank(), "英文空值: " + key);
        });
    }

    private Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource: " + path);
            p.load(in);
        }
        return p;
    }
}
