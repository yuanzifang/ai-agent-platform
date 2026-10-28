package com.platform.common.config;

import com.platform.common.security.CryptoService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台管理页保存的本地基础设施配置。
 *
 * <p>文件默认是工作目录下的 config/application-platform.yml，可用
 * PLATFORM_INFRA_CONFIG 覆盖。它在所有 application.yml / 环境变量之前加载，
 * 因此页面保存的配置可以覆盖启动脚本里的连接参数；必须重启后端才生效。</p>
 */
public class LocalInfraConfigPostProcessor implements EnvironmentPostProcessor {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = System.getenv().getOrDefault("PLATFORM_INFRA_CONFIG",
                "config/application-platform.yml");
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return;
        List<PropertySource<?>> sources;
        try {
            Resource resource = new FileSystemResource(path);
            sources = loader.load("platform-infra", resource);
        } catch (Exception ignored) {
            // YAML 损坏时保留 application.yml/env 默认值，避免应用无法启动。
            return;
        }
        // 使用与 CryptoService 相同的密钥材料解密平台页保存的 v1: 密文。
        CryptoService crypto = new CryptoService(
                environment.getProperty("security.aes-key", ""),
                environment.getProperty("security.jwt.secret", ""));
        for (int i = sources.size() - 1; i >= 0; i--) {
            environment.getPropertySources().addFirst(decryptSource(sources.get(i), crypto));
        }
    }

    private PropertySource<?> decryptSource(PropertySource<?> source, CryptoService crypto) {
        if (!(source instanceof EnumerablePropertySource<?> enumerable)) return source;
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : enumerable.getPropertyNames()) {
            Object value = enumerable.getProperty(name);
            if (value instanceof String text && text.startsWith("v1:")) {
                try {
                    value = crypto.decrypt(text);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "平台基础设施配置解密失败，请检查 AES_KEY/JWT_SECRET 是否与保存时一致：" + name, e);
                }
            }
            values.put(name, value);
        }
        return new MapPropertySource(source.getName() + "-decrypted", values);
    }
}
