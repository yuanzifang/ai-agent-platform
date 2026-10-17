package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_operation")
public class AuditOperation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private String opType;
    private String objectType;
    private String objectId;
    private String opResult;
    private String detail;
    private String ip;
    private String prevHash;
    private LocalDateTime createdAt;
}
