package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE sys_user SET password_hash = #{hash}, password_updated_at = #{time} WHERE id = #{id}")
    int updatePasswordIgnore(@Param("id") Long id, @Param("hash") String hash,
                             @Param("time") java.time.LocalDateTime time);


    /** 登录链路：匿名无租户上下文，豁免多租户过滤（架构 2.2）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND status != 'DELETED' LIMIT 1")
    SysUser findByUsername(String username);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE id = #{id} LIMIT 1")
    SysUser findByIdIgnore(Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE sys_user SET last_login_at = NOW() WHERE id = #{id}")
    void touchLastLogin(Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT r.role_key FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> findRoleKeys(Long userId);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE sys_user SET password_hash = #{hash}, password_updated_at = NOW() "
            + "WHERE password_hash = '__RESET__' AND is_platform_admin = 1")
    int resetPlatformAdminPassword(String hash);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE sys_user SET password_hash = #{hash}, password_updated_at = NOW() "
            + "WHERE password_hash = '__RESET__' AND username = 'dev'")
    int resetDevPassword(String hash);

    /** 用户权限点（角色→权限，需求 3.5）；平台管理员在 Service 层直接放行为全量。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT DISTINCT p.perm_key FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId}
            """)
    java.util.List<String> findPermKeys(Long userId);

    /** 用户名全局唯一校验（平台开通租户时无租户上下文）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
    Long countByUsernameGlobal(String username);
}