package com.platform.capability.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.capability.entity.SkillRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkillRegistryMapper extends BaseMapper<SkillRegistry> {

    /** 内置（tenant_id IS NULL）+ 本租户技能；绕过租户插件显式过滤（架构 2.2 OR NULL 模式）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM skill_registry
            WHERE status <> 'DELETED' AND (tenant_id IS NULL OR tenant_id = #{tenantId})
            ORDER BY is_builtin DESC, id ASC
            """)
    List<SkillRegistry> selectVisible(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM skill_registry
            WHERE id = #{id} AND status <> 'DELETED' AND (tenant_id IS NULL OR tenant_id = #{tenantId})
            """)
    SkillRegistry findVisible(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM skill_registry WHERE skill_key = #{skillKey}")
    long countByKey(@Param("skillKey") String skillKey);
}
