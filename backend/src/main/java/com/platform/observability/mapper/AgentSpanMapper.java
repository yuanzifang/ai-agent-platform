package com.platform.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.observability.entity.AgentSpan;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentSpanMapper extends BaseMapper<AgentSpan> {
}
