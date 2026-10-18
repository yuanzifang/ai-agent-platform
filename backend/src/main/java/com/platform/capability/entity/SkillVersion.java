package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_version")
public class SkillVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private String version;
    private String snapshot;   // JSON
    private Long createdBy;
    private Long tenantId;
    private LocalDateTime createdAt;
}
