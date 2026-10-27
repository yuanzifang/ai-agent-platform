package com.platform.i18n.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("i18n_message")
public class I18nMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 语言标签（BCP 47：zh-CN / en-US / ja-JP …） */
    private String lang;

    /** 点号分层键（与前端 i18n 键一致，如 menu.agents） */
    private String messageKey;

    private String content;

    /** 键用途说明（管理端可编辑） */
    private String description;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
