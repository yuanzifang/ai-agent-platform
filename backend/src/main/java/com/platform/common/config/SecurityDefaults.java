package com.platform.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

/**
 * 安全默认值的单一来源：读取 {@code security-defaults.json}。
 *
 * <p>ProductionGuard 与 scripts/check-mvp-freeze.py 使用同一文件，避免新增默认值时
 * 只改其中一套检查。</p>
 */
public final class SecurityDefaults {

    private static final Map<String, Object> DATA = load();

    private SecurityDefaults() {
    }

    public static String runtime(String section, String key) {
        return nested(DATA, "runtime", section, key);
    }

    private static String nested(Map<String, Object> root, String... path) {
        Object current = root;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current == null ? null : String.valueOf(current);
    }

    private static Map<String, Object> load() {
        try (InputStream input = new ClassPathResource("security-defaults.json").getInputStream()) {
            return new ObjectMapper().readValue(input, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("无法加载 security-defaults.json", e);
        }
    }
}
