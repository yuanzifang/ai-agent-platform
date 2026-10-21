package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("case_calibration_label")
public class CaseCalibrationLabel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long runItemId;
    private Long reviewerId;
    private BigDecimal humanScore;
    private String humanLabel;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
