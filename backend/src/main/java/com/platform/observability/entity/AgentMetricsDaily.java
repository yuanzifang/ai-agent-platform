package com.platform.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 监控日汇总（需求 6.13）：仅可加性指标，由定时任务每日生成。 */
@Data
@TableName("agent_metrics_daily")
public class AgentMetricsDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate statDate;
    private Long tenantId;
    private Long agentId;
    private BigDecimal cost;
    private Long calls;
    private Long sessions;     // 按 session 首日归属，避免跨天重复
    private Long activeUsers;
    private Long tokens;
    private Long errorCount;
    private LocalDateTime createdAt;
}
