package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_dependency")
public class SkillDependency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private String depType;   // TOOL / KNOWLEDGE_BASE
    private Long depId;
    private Long tenantId;
    private LocalDateTime createdAt;
}
