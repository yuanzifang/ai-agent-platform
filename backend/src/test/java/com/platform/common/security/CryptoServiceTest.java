package com.platform.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoServiceTest {

    @Test
    void encryptDecryptRoundTrip() {
        CryptoService service = new CryptoService("unit-test-aes-key-material-0123456789", "jwt-secret");
        String encrypted = service.encrypt("sk-secret-value");
        assertNotEquals("sk-secret-value", encrypted);
        assertEquals("sk-secret-value", service.decrypt(encrypted));
    }

    @Test
    void nullAndBlankHandling() {
        CryptoService service = new CryptoService("", "jwt-secret");
        assertNull(service.encrypt(null));
        assertNull(service.encrypt("  "));
        assertNull(service.decrypt(null));
    }

    @Test
    void wrongKeyFailsDecrypt() {
        CryptoService a = new CryptoService("key-a-material-0123456789", "jwt-a");
        CryptoService b = new CryptoService("key-b-material-0123456789", "jwt-b");
        String encrypted = a.encrypt("secret");
        assertThrows(IllegalArgumentException.class, () -> b.decrypt(encrypted));
    }
}
