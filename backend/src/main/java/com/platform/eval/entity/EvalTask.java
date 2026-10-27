package com.platform.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("eval_task")
public class EvalTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long agentId;
    private String agentVersion;
    private String targetType;
    private String evalType;

    /** 灰度标记：评测运行分组维度（轨迹 tags 记 gray:<tag>，供 GrayCompareService 聚合对比） */
    private String grayTag;
    private String filterSnapshot;
    private Integer matchedTraces;
    private String judgeModel;
    private String metrics;
    private Integer rounds;
    private String status;
    private Integer estimatedCalls;
    private Integer completedCalls;
    private BigDecimal avgScore;
    private BigDecimal passRate;
    private Long datasetId;
    private Long tenantId;
    private Long createdBy;
    private LocalDateTime createdAt;
}
