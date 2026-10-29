package com.platform.gateway.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.gateway.entity.GatewayRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GatewayRouteMapper extends BaseMapper<GatewayRoute> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM gateway_route WHERE route_key = #{routeKey} LIMIT 1")
    GatewayRoute findByRouteKeyIgnore(@Param("routeKey") String routeKey);

    /** 健康检查调度跨租户扫描启用路由。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM gateway_route WHERE enabled = 1")
    java.util.List<GatewayRoute> listEnabledIgnore();
}
