package com.platform.model.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.model.entity.ModelEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelMapper extends BaseMapper<ModelEntity> {

    /** 平台预置模型插入（tenant_id 由调用方显式指定，绕过租户插件自动注入）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO model (provider_id, model_name, display_name, purpose, capabilities,
                               default_params, input_price, output_price, status, tenant_id)
            VALUES (#{m.providerId}, #{m.modelName}, #{m.displayName}, #{m.purpose}, #{m.capabilities},
                    #{m.defaultParams}, #{m.inputPrice}, #{m.outputPrice}, #{m.status}, #{m.tenantId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertIgnoreTenant(@Param("m") ModelEntity m);

    /** 更新（绕过租户插件，兼容平台预置行）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE model SET display_name = #{m.displayName}, capabilities = #{m.capabilities},
                   default_params = #{m.defaultParams}, status = #{m.status}
            WHERE id = #{m.id}
            """)
    int updateIgnoreTenant(@Param("m") ModelEntity m);
}
