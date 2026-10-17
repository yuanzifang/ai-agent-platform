package com.platform.model.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProviderMapper extends BaseMapper<Provider> {

    /** 平台预置 + 本租户（架构 2.2 OR NULL 模式）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM provider
            WHERE status = 'ENABLED' AND (tenant_id IS NULL OR tenant_id = #{tenantId})
            """)
    List<Provider> listVisible(Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM provider WHERE id = #{id} LIMIT 1")
    Provider findByIdIgnore(Long id);

    /** 模型可见范围 = 平台预置（未被租户禁用）+ 本租户自建。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT m.* FROM model m
            LEFT JOIN provider p ON p.id = m.provider_id
            WHERE m.status = 'ENABLED'
              AND (p.tenant_id IS NULL OR p.tenant_id = #{tenantId})
            ORDER BY m.id
            """)
    List<ModelEntity> listVisibleModels(Long tenantId);
}
