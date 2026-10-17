package com.platform.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("eval_result")
public class EvalResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long datasetItemId;
    private String traceId;
    private String metricKey;
    private String answer;
    private BigDecimal score;
    private Integer passed;
    private String judgeReason;
    private Long latencyMs;
    private Integer tokens;
    private Long tenantId;
    private LocalDateTime createdAt;
}
