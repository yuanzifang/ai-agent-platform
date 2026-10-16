package com.platform.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_session")
public class UserSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String refreshTokenHash;
    private String device;
    private String ip;
    private LocalDateTime expiresAt;
    private Integer revoked;
    private LocalDateTime createdAt;
}
