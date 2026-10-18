package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 内置角色 tenant_id 为空，绕过租户插件（SSO 自动开通分配默认角色）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_role WHERE role_key = #{roleKey} AND tenant_id IS NULL LIMIT 1")
    SysRole findBuiltinByKey(@Param("roleKey") String roleKey);
}
