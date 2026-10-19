package com.platform.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("provider_credential")
public class ProviderCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String name;
    private String encryptedContent;
    private String status;
    private Long tenantId;
    private LocalDateTime createdAt;
}
