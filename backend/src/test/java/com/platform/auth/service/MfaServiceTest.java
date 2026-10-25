package com.platform.auth.service;

import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.system.entity.MfaRecoveryCode;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.MfaRecoveryCodeMapper;
import com.platform.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MFA TOTP/恢复码回归测试：密钥加密、一次性恢复码、错误码拒绝。 */
class MfaServiceTest {

    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private SysUserMapper userMapper;
    private MfaRecoveryCodeMapper recoveryMapper;
    private CryptoService cryptoService;
    private MfaService service;
    private SysUser user;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        recoveryMapper = mock(MfaRecoveryCodeMapper.class);
        cryptoService = new CryptoService("unit-test-aes-key-material-0123456789", "jwt-secret");
        service = new MfaService(userMapper, recoveryMapper, cryptoService);

        user = new SysUser();
        user.setId(1L);
        user.setTenantId(9L);
        user.setUsername("alice");
        user.setMfaEnabled(0);
        when(userMapper.findByIdIgnore(1L)).thenReturn(user);
    }

    @Test
    void setupStoresEncryptedSecretAndSecretDecrypts() {
        service.setup(1L);

        assertTrue(user.getMfaSecret().startsWith("v1:"));
        assertNotEquals(user.getMfaSecret(), service.secret(1L));
        assertFalse(service.secret(1L).isBlank());
        verify(userMapper).updateById(user);
    }

    @Test
    void totpMatchesRfc6238VectorAndAcceptsWindow() {
        assertEquals("287082", service.totp(RFC_SECRET, 1L));

        long counter = Instant.now().getEpochSecond() / 30;
        String current = service.totp(RFC_SECRET, counter);
        assertTrue(service.verifyTotp(RFC_SECRET, current));
        assertTrue(service.verifyTotp(RFC_SECRET, service.totp(RFC_SECRET, counter + 1)));
        assertFalse(service.verifyTotp(RFC_SECRET, service.totp(RFC_SECRET, counter + 2)));
        assertFalse(service.verifyTotp(RFC_SECRET, "12345"));
    }

    @Test
    void enableRejectsWrongCodeAndCreatesTenRecoveryCodes() {
        user.setMfaSecret(cryptoService.encrypt(RFC_SECRET));
        Instant now = Instant.now();
        String current = service.totp(RFC_SECRET, now.getEpochSecond() / 30);

        BusinessException wrong = assertThrows(BusinessException.class,
                () -> service.enable(1L, "not-a-code"));
        assertEquals(400, wrong.getCode());
        verify(userMapper, never()).updateById(user);

        List<String> codes = service.enable(1L, current);

        assertEquals(10, codes.size());
        assertEquals(10, new HashSet<>(codes).size());
        assertTrue(codes.stream().allMatch(c -> c.matches("[0-9A-F]{5}-[0-9A-F]{5}")));
        assertEquals(1, user.getMfaEnabled());
        verify(recoveryMapper).deleteByUser(1L);
        ArgumentCaptor<MfaRecoveryCode> rows = ArgumentCaptor.forClass(MfaRecoveryCode.class);
        verify(recoveryMapper, times(10)).insert(rows.capture());
        assertTrue(rows.getAllValues().stream().allMatch(r ->
                r.getCodeHash().matches("[0-9a-f]{64}") && r.getUsed() == 0
                        && r.getTenantId().equals(9L)));
        assertFalse(rows.getAllValues().stream().anyMatch(r ->
                codes.stream().anyMatch(c -> c.replace("-", "").equals(r.getCodeHash()))));
    }

    @Test
    void loginAcceptsTotpAndConsumesRecoveryCode() {
        user.setMfaEnabled(1);
        user.setMfaSecret(cryptoService.encrypt(RFC_SECRET));
        long counter = Instant.now().getEpochSecond() / 30;
        service.verifyLoginCode(1L, service.totp(RFC_SECRET, counter));

        MfaRecoveryCode recovery = new MfaRecoveryCode();
        recovery.setId(55L);
        recovery.setUserId(1L);
        when(recoveryMapper.findUnused(eq(1L), anyString())).thenReturn(recovery);
        service.verifyLoginCode(1L, "ABCDE-12345");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(recoveryMapper).findUnused(eq(1L), hash.capture());
        assertTrue(hash.getValue().matches("[0-9a-f]{64}"));
        assertNotEquals("ABCDE12345", hash.getValue());
        verify(recoveryMapper).markUsed(55L);
    }

    @Test
    void invalidLoginCodeIsRejectedAndNotMarkedUsed() {
        user.setMfaEnabled(1);
        user.setMfaSecret(cryptoService.encrypt(RFC_SECRET));
        when(recoveryMapper.findUnused(eq(1L), anyString())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.verifyLoginCode(1L, "abc"));

        assertEquals(401, error.getCode());
        verify(recoveryMapper, never()).markUsed(anyLong());
    }

    @Test
    void disabledMfaIsNoOpForLoginVerification() {
        user.setMfaEnabled(0);
        service.verifyLoginCode(1L, null);
        verify(recoveryMapper, never()).findUnused(any(), anyString());
    }

    @Test
    void disableClearsSecretAndRecoveryCodes() {
        user.setMfaEnabled(1);
        user.setMfaSecret(cryptoService.encrypt(RFC_SECRET));
        long counter = Instant.now().getEpochSecond() / 30;

        service.disable(1L, service.totp(RFC_SECRET, counter));

        assertEquals(0, user.getMfaEnabled());
        assertNull(user.getMfaSecret());
        verify(userMapper).updateById(user);
        verify(recoveryMapper).deleteByUser(1L);
    }

    @Test
    void otpauthUriContainsIssuerAndSecret() {
        String uri = service.otpauthUri("alice", RFC_SECRET);
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("issuer=AI%20Agent%20Platform"));
        assertTrue(uri.contains("secret=" + RFC_SECRET));
        assertTrue(uri.contains("alice"));
    }
}
