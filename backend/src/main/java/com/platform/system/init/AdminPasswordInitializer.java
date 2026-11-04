package com.platform.system.init;

import com.platform.common.config.ProductionGuard;
import com.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 平台管理员 + 开发账号密码初始化（架构 10.8）：仅当 seed 占位 __RESET__ 存在时生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminPasswordInitializer implements ApplicationRunner {

    /** 本地开发便利默认值；生产模式禁止使用，未设置时 dev 账号直接不激活。 */
    static final String DEV_DEFAULT_PASSWORD = "Dev@123456";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;

    @Override
    public void run(ApplicationArguments args) {
        String password = env.getProperty("PLATFORM_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            log.warn("未配置 PLATFORM_ADMIN_PASSWORD：平台管理员 admin 未激活");
        } else if (userMapper.resetPlatformAdminPassword(passwordEncoder.encode(password)) > 0) {
            log.info("平台管理员 admin 初始密码已设置");
        }

        String devPassword = env.getProperty("DEV_PASSWORD");
        if (ProductionGuard.isProduction(env)) {
            if (devPassword == null || devPassword.isBlank()) {
                log.warn("生产环境未配置 DEV_PASSWORD：开发账号 dev 不激活");
                return;
            }
            if (DEV_DEFAULT_PASSWORD.equals(devPassword)) {
                throw new IllegalStateException(
                        "生产环境 DEV_PASSWORD 不能使用默认开发密码 " + DEV_DEFAULT_PASSWORD + "，拒绝启动");
            }
        } else if (devPassword == null || devPassword.isBlank()) {
            devPassword = DEV_DEFAULT_PASSWORD;
        }
        if (userMapper.resetDevPassword(passwordEncoder.encode(devPassword)) > 0) {
            log.info("开发账号 dev 初始密码已按 DEV_PASSWORD 设置");
        }
    }
}
