package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private Integer isPlatformAdmin;
    private Integer isServiceAccount;
    private Long tenantId;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lockedUntil;
    private java.time.LocalDateTime passwordUpdatedAt;
    private Integer mfaEnabled;
    private String mfaSecret;
    private LocalDateTime createdAt;
}
