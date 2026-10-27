package com.platform.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        try {
            Resource resource = new FileSystemResource(path);
            List<PropertySource<?>> sources = loader.load("platform-infra", resource);
            for (int i = sources.size() - 1; i >= 0; i--) {
                environment.getPropertySources().addFirst(sources.get(i));
            }
        } catch (Exception ignored) {
            // 配置损坏时保留 application.yml/env 默认值，避免应用无法启动。
        }
    }
}
