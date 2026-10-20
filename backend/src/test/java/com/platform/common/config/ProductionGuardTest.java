package com.platform.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionGuardTest {

    private static final String GOOD_JWT = "0123456789abcdef0123456789abcdef";
    private static final String GOOD_AES = "abcdef0123456789abcdef0123456789";

    @Test
    void developmentAllowsWeakDefaults() {
        MockEnvironment env = new MockEnvironment();
        assertDoesNotThrow(() -> new ProductionGuard(env, "dev-only-secret-change-me-0123456789abcdef",
                "", "root", "minioadmin", "minioadmin", "sk-not-configured"));
    }

    @Test
    void productionRejectsWeakDefaults() {
        MockEnvironment env = new MockEnvironment().withProperty("APP_ENV", "production");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ProductionGuard(env, "dev-only-secret-change-me-0123456789abcdef",
                        "", "root", "minioadmin", "minioadmin", "sk-not-configured"));
        assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
        assertTrue(e.getMessage().contains("MYSQL_PASSWORD"), e.getMessage());
        assertTrue(e.getMessage().contains("DEEPSEEK_API_KEY"), e.getMessage());
    }

    @Test
    void productionAcceptsStrongConfig() {
        MockEnvironment env = new MockEnvironment().withProperty("APP_ENV", "prod");
        assertDoesNotThrow(() -> new ProductionGuard(env, GOOD_JWT, GOOD_AES,
                "strong-db-password", "minio-user", "minio-strong-secret", "sk-real-configured"));
    }
}
