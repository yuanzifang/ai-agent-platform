package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 单次写工具调用审批：命中 APPROVAL 策略时只创建审批，不执行工具。 */
@Data
@TableName("tool_approval")
public class ToolApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long toolId;
    private String toolKey;
    private String toolName;
    private String toolType;
    private String toolInput;
    private String status; // PENDING / APPROVED / REJECTED / EXECUTED / FAILED
    private Long requestedBy;
    private Long decidedBy;
    private String comment;
    private String result;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
