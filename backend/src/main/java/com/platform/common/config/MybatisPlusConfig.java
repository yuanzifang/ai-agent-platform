package com.platform.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.platform.common.context.TenantContext;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 多租户插件（架构 2.2）：
 * - 无 tenant_id 的表走 ignoreTable；
 * - 平台预置行（tenant_id 为空）的表不在 ignoreTable，Mapper 层用 (tenant_id = ? OR tenant_id IS NULL) 或 @InterceptorIgnore；
 * - 登录链路（匿名、无租户上下文）的 Mapper 方法统一加 @InterceptorIgnore。
 */
@Configuration
public class MybatisPlusConfig {

    /** 无 tenant_id 列的表（架构 2.2 ignoreTable 清单）。 */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "sys_tenant", "sys_permission", "sys_user_role", "user_session",
            "sys_config", "sys_role", "audit_login");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public LongValue getTenantId() {
                Long tenantId = TenantContext.getTenantId();
                if (tenantId == null) {
                    // 平台管理员未选择租户等场景：返回明确 403 而非误查全租户
                    throw new com.platform.common.exception.BusinessException(
                            403, "缺少租户上下文：平台管理员请携带 X-Tenant-Id 选择租户");
                }
                return new LongValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return IGNORE_TABLES.contains(tableName.toLowerCase());
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
