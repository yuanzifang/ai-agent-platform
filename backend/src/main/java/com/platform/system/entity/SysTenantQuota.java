package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_tenant_quota")
public class SysTenantQuota {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Integer maxAgents;
    private Integer maxKnowledgeBases;
    private Long maxTokensPerDay;
    private Long maxStorageBytes;
    private Integer maxApiCallsPerMin;
}
