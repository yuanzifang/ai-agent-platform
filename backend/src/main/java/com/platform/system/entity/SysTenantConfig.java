package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_tenant_config")
public class SysTenantConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String configKey;
    private String configValue;
    private String description;
    private LocalDateTime updatedAt;
}
