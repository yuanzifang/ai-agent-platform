package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_registry")
public class SkillRegistry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String skillKey;
    private String name;
    private String description;
    private String iconUrl;
    private String category;
    private String tags;        // JSON array
    private String skillType;   // BUILTIN / CUSTOM
    private String prompt;
    private String params;          // JSON array（参数定义）
    private String scriptLanguage;  // NONE / PYTHON / JAVASCRIPT
    private String scriptCode;      // 脚本正文（stdout 作为工具结果）
    private Integer scriptTimeoutMs;
    private String status;      // ENABLED / DISABLED / DELETED
    private Integer isBuiltin;
    private Long tenantId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
