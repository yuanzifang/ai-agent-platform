package com.platform.auth.service;

import com.platform.auth.mapper.PasswordResetTokenMapper;
import com.platform.auth.mapper.UserSessionMapper;
import com.platform.common.exception.BusinessException;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.AuditLoginMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.security.JwtService;
import com.platform.common.mail.MailTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0：密码错误计数，达到阈值后锁定。 */
class AuthServiceLoginTest {

    @Test
    void failedPasswordIncrementsAttemptsAndLocksAtThreshold() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditLoginMapper auditMapper = mock(AuditLoginMapper.class);
        AuthService service = service(userMapper, encoder, auditMapper);
        ReflectionTestUtils.setField(service, "maxLoginFailures", 2);
        ReflectionTestUtils.setField(service, "loginLockMinutes", 10);

        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("user");
        user.setStatus("ENABLED");
        user.setPasswordHash("hash");
        user.setFailedAttempts(1);
        when(userMapper.findByUsername("user")).thenReturn(user);
        when(encoder.matches("bad", "hash")).thenReturn(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.login("user", "bad", null, request));

        assertEquals(401, error.getCode());
        ArgumentCaptor<LocalDateTime> locked = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userMapper).updateLoginFailure(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(2), locked.capture());
        assertNotNull(locked.getValue(), "达到阈值必须写入锁定时间");
        verify(auditMapper).insert(any());
    }

    @Test
    void alreadyLockedAccountReturns423WithoutPasswordCheck() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthService service = service(userMapper, encoder, mock(AuditLoginMapper.class));
        SysUser user = new SysUser();
        user.setId(8L);
        user.setUsername("locked");
        user.setStatus("ENABLED");
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(userMapper.findByUsername("locked")).thenReturn(user);
        HttpServletRequest request = mock(HttpServletRequest.class);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.login("locked", "anything", null, request));

        assertEquals(423, error.getCode());
    }

    private AuthService service(SysUserMapper userMapper, PasswordEncoder encoder, AuditLoginMapper auditMapper) {
        return new AuthService(userMapper, mock(SysTenantMapper.class), mock(UserSessionMapper.class),
                auditMapper, mock(JwtService.class), encoder, mock(MfaService.class),
                mock(PasswordResetTokenMapper.class), mock(MailTemplateService.class));
    }
}
