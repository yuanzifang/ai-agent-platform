package com.platform.system.init;

import com.platform.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPasswordInitializerTest {

    @Test
    void devPasswordMissingInProductionSkipsDevAccount() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        AdminPasswordInitializer initializer = new AdminPasswordInitializer(
                userMapper, encoder(), environment("production", null));

        initializer.run(null);

        verify(userMapper, never()).resetDevPassword(anyString());
    }

    @Test
    void defaultDevPasswordInProductionRefusesToStart() {
        AdminPasswordInitializer initializer = new AdminPasswordInitializer(
                mock(SysUserMapper.class), encoder(), environment("production", "Dev@123456"));

        assertThrows(IllegalStateException.class, () -> initializer.run(null));
    }

    @Test
    void devPasswordMissingOutsideProductionFallsBackToDefault() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        PasswordEncoder encoder = encoder();
        when(encoder.encode("Dev@123456")).thenReturn("hashed-dev");
        when(userMapper.resetDevPassword("hashed-dev")).thenReturn(1);
        AdminPasswordInitializer initializer = new AdminPasswordInitializer(
                userMapper, encoder, environment("development", null));

        initializer.run(null);

        verify(userMapper).resetDevPassword("hashed-dev");
    }

    @Test
    void providedDevPasswordIsApplied() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        PasswordEncoder encoder = encoder();
        when(encoder.encode("Strong#Pass1")).thenReturn("hashed-strong");
        when(userMapper.resetDevPassword("hashed-strong")).thenReturn(1);
        AdminPasswordInitializer initializer = new AdminPasswordInitializer(
                userMapper, encoder, environment("development", "Strong#Pass1"));

        initializer.run(null);

        verify(userMapper).resetDevPassword("hashed-strong");
    }

    private PasswordEncoder encoder() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hashed");
        return encoder;
    }

    private Environment environment(String appEnv, String devPassword) {
        Environment env = mock(Environment.class);
        when(env.getProperty("APP_ENV", "development")).thenReturn(appEnv);
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        when(env.getProperty("PLATFORM_ADMIN_PASSWORD")).thenReturn(null);
        when(env.getProperty("DEV_PASSWORD")).thenReturn(devPassword);
        return env;
    }
}
