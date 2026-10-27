package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("retrieval_resource_profile")
public class ResourceProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String searchBackend;
    private String vectorBackend;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
