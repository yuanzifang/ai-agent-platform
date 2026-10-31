package com.platform.deploy.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.deploy.entity.AgentDeployment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentDeploymentMapper extends BaseMapper<AgentDeployment> {

    /** 公开运行时鉴权时先按 agentId 找部署，再按部署 tenant 还原租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM agent_deployment WHERE agent_id = #{agentId} LIMIT 1")
    AgentDeployment findByAgentIdIgnore(@Param("agentId") Long agentId);
}
