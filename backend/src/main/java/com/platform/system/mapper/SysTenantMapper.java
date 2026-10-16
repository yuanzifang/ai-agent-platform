package com.platform.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.system.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_tenant WHERE tenant_key = #{tenantKey} LIMIT 1")
    SysTenant findByKey(String tenantKey);
}
