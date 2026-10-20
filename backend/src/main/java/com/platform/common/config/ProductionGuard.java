package com.platform.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 生产环境弱默认值熔断：APP_ENV=production/prod 或 spring profile 含 prod 时，
 * 缺少/使用开发默认密钥则拒绝启动，避免生产漏配后静默可用。
 */
@Component
public class ProductionGuard {

    public ProductionGuard(Environment environment,
                           @Value("${security.jwt.secret:}") String jwtSecret,
                           @Value("${security.aes-key:}") String aesKey,
                           @Value("${spring.datasource.password:}") String dbPassword,
                           @Value("${storage.minio.access-key:}") String minioAccessKey,
                           @Value("${storage.minio.secret-key:}") String minioSecretKey,
                           @Value("${spring.ai.openai.api-key:}") String modelApiKey) {
        if (!isProduction(environment)) return;
        List<String> problems = new ArrayList<>();
        if (blank(jwtSecret) || jwtSecret.startsWith("dev-only-secret") || jwtSecret.length() < 32) {
            problems.add("JWT_SECRET 未配置或仍为开发默认值（要求 >=32 字符）");
        }
        if (blank(aesKey) || aesKey.length() < 32) {
            problems.add("AES_KEY 未配置或长度不足 32");
        }
        if (blank(dbPassword) || "root".equals(dbPassword)) {
            problems.add("MYSQL_PASSWORD 未配置或仍为 root");
        }
        if (blank(minioAccessKey) || blank(minioSecretKey)
                || "minioadmin".equals(minioAccessKey) || "minioadmin".equals(minioSecretKey)) {
            problems.add("MINIO_ACCESS_KEY/MINIO_SECRET_KEY 未配置或仍为 minioadmin");
        }
        if (blank(modelApiKey) || "sk-not-configured".equals(modelApiKey)) {
            problems.add("DEEPSEEK_API_KEY 未配置");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产环境配置校验失败，拒绝启动：\n - "
                    + String.join("\n - ", problems));
        }
    }

    private boolean isProduction(Environment environment) {
        String appEnv = environment.getProperty("APP_ENV", "development").toLowerCase(Locale.ROOT);
        if (List.of("prod", "production").contains(appEnv)) return true;
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
