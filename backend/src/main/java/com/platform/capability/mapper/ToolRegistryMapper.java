package com.platform.capability.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.capability.entity.ToolRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ToolRegistryMapper extends BaseMapper<ToolRegistry> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM tool_registry
            WHERE status <> 'DELETED' AND (tenant_id IS NULL OR tenant_id = #{tenantId})
            ORDER BY is_builtin DESC, id ASC
            """)
    List<ToolRegistry> selectVisible(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM tool_registry
            WHERE id = #{id} AND status <> 'DELETED' AND (tenant_id IS NULL OR tenant_id = #{tenantId})
            """)
    ToolRegistry findVisible(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 全局 key 唯一校验（含软删除行，避免 key 复用）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM tool_registry WHERE tool_key = #{toolKey}")
    long countByKey(@Param("toolKey") String toolKey);
}
