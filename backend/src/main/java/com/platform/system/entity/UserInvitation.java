package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_invitation")
public class UserInvitation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String email;
    private String role;
    private String token;
    private String status;
    private Long invitedBy;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
