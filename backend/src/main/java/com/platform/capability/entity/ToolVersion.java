package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_version")
public class ToolVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long toolId;
    private String version;
    private String snapshot;   // JSON
    private Long createdBy;
    private Long tenantId;
    private LocalDateTime createdAt;
}
