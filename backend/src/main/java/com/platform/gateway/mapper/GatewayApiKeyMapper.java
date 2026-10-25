package com.platform.gateway.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.gateway.entity.GatewayApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GatewayApiKeyMapper extends BaseMapper<GatewayApiKey> {

    /** 公开代理入口：无租户上下文，按哈希取 Key。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM gateway_api_key WHERE key_hash = #{keyHash} LIMIT 1")
    GatewayApiKey findByHash(@Param("keyHash") String keyHash);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM gateway_api_key WHERE route_id = #{routeId} ORDER BY id")
    List<GatewayApiKey> listByRoute(@Param("routeId") Long routeId);
}
