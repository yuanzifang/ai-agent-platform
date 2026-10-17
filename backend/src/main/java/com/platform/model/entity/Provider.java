package com.platform.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("provider")
public class Provider {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String providerType;
    private String baseUrl;
    private String proxy;
    private Integer timeoutMs;
    private Integer retryTimes;
    private String status;
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
