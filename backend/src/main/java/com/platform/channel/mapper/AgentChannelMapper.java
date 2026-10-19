package com.platform.channel.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.channel.entity.AgentChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentChannelMapper extends BaseMapper<AgentChannel> {

    /** 公开入站：按 token 查渠道（无租户上下文，绕过租户插件）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM agent_channel WHERE token = #{token} LIMIT 1")
    AgentChannel findByTokenIgnoreTenant(@Param("token") String token);
}
