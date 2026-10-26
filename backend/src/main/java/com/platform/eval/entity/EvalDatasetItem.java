package com.platform.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eval_dataset_item")
public class EvalDatasetItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String question;
    private String expectedAnswer;
    private String scoreCriteria;
    private String stepType;
    private String expectedPayload;
    private String stepConfig;
    private Long tenantId;
    private LocalDateTime createdAt;
}
