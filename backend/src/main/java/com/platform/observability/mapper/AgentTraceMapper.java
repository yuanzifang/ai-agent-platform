package com.platform.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.observability.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {
}
