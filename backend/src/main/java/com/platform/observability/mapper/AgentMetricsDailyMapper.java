package com.platform.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.observability.entity.AgentMetricsDaily;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMetricsDailyMapper extends BaseMapper<AgentMetricsDaily> {
}
