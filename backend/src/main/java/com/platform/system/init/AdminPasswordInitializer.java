package com.platform.system.init;

import com.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 平台管理员初始密码重置（架构 10.8）：seed 账号 password_hash='__RESET__'，
 * 启动时若配置了 PLATFORM_ADMIN_PASSWORD 则重置；未配置则提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminPasswordInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;

    @Override
    public void run(ApplicationArguments args) {
        String password = env.getProperty("PLATFORM_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            log.warn("未配置 PLATFORM_ADMIN_PASSWORD：平台管理员 admin 未激活，"
                    + "请设置该环境变量后重启，或手动执行重置");
            return;
        }
        int updated = userMapper.resetPlatformAdminPassword(passwordEncoder.encode(password));
        if (updated > 0) {
            log.info("平台管理员 admin 初始密码已设置（来源：PLATFORM_ADMIN_PASSWORD）");
        }
    }
}
