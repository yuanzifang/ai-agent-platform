package com.platform.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.entity.AgentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentVersionMapper extends BaseMapper<AgentVersion> {

    @Select("SELECT COUNT(*) FROM agent_version WHERE agent_id = #{agentId}")
    int countByAgent(Long agentId);
}
