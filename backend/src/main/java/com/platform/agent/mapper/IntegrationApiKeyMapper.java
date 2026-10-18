package com.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.entity.IntegrationApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IntegrationApiKeyMapper extends BaseMapper<IntegrationApiKey> {

    /** 外部鉴权时无租户上下文，按 key 哈希跨租户查找（哈希全局唯一）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM agent_integration_api_key WHERE key_hash = #{keyHash} LIMIT 1")
    IntegrationApiKey findByHashIgnore(@Param("keyHash") String keyHash);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM agent_integration_api_key WHERE id = #{id}")
    IntegrationApiKey findByIdIgnore(@Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE agent_integration_api_key SET last_used_at = #{time} WHERE id = #{id}")
    int touch(@Param("id") Long id, @Param("time") LocalDateTime time);
}
