package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mfa_recovery_code")
public class MfaRecoveryCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String codeHash;
    private Integer used;
    private Long tenantId;
    private LocalDateTime createdAt;
}
