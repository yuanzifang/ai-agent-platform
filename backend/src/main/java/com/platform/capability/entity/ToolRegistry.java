package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_registry")
public class ToolRegistry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String toolKey;
    private String name;
    private String description;
    private String iconUrl;
    private String category;
    private String tags;        // JSON array
    private String toolType;    // BUILTIN / API / MCP
    private String apiConfig;   // JSON：地址/方法/请求头/参数/响应解析/鉴权/inputSchema
    private Long mcpServerId;
    private Integer timeoutMs;
    private Integer retryTimes;
    private String status;      // ENABLED / DISABLED / DELETED
    private Integer isBuiltin;
    private Long tenantId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
