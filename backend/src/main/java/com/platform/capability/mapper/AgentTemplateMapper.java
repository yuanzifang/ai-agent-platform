package com.platform.capability.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.capability.entity.AgentTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentTemplateMapper extends BaseMapper<AgentTemplate> {

    /** 内置（tenant_id IS NULL）+ 本租户模板。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM agent_template
            WHERE tenant_id IS NULL OR tenant_id = #{tenantId}
            ORDER BY is_builtin DESC, id ASC
            """)
    List<AgentTemplate> selectVisible(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM agent_template
            WHERE id = #{id} AND (tenant_id IS NULL OR tenant_id = #{tenantId})
            """)
    AgentTemplate findVisible(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
