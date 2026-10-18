package com.platform.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.agent.entity.AgentSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentScheduleMapper extends BaseMapper<AgentSchedule> {

    /** 调度器无租户上下文，跨租户取到期任务（随后逐个以任务租户执行）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM agent_schedule
            WHERE status = 'ENABLED' AND next_run_at IS NOT NULL AND next_run_at <= #{now}
            ORDER BY next_run_at ASC LIMIT 50
            """)
    List<AgentSchedule> selectDue(@Param("now") LocalDateTime now);
}
