package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_schedule_result")
public class AgentScheduleResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scheduleId;
    private Long tenantId;
    private String traceId;
    private String status;
    private String answer;
    private String error;
    private LocalDateTime createdAt;
}
