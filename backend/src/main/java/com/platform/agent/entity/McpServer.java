package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_server")
public class McpServer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String endpoint;
    private String transport;      // HTTP / SSE / STDIO
    private String command;        // STDIO 启动命令
    private String args;           // STDIO 启动参数（JSON 数组）
    private String envEncrypted;   // STDIO 环境变量（AES-GCM 密文 JSON）
    private String authType;      // NONE / API_KEY / BEARER / OAUTH
    private String encryptedAuth; // AES-GCM 密文（JSON）
    private String customHeaders; // JSON：自定义请求头
    private String status;        // ENABLED / DISABLED
    private String healthStatus;  // ONLINE / OFFLINE / UNKNOWN
    private LocalDateTime lastSyncAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime toolsChangedAt;
    private Long tenantId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
