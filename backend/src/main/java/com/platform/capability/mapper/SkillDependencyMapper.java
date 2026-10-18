package com.platform.capability.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.capability.entity.SkillDependency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkillDependencyMapper extends BaseMapper<SkillDependency> {

    /** 内置技能的依赖 tenant_id 为空，需绕过租户插件显式读取。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM skill_dependency WHERE skill_id = #{skillId} ORDER BY id")
    List<SkillDependency> selectBySkill(@Param("skillId") Long skillId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM skill_dependency WHERE dep_type = 'TOOL' AND dep_id = #{toolId}")
    List<SkillDependency> selectByTool(@Param("toolId") Long toolId);
}
