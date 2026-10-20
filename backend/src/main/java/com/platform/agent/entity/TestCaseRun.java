package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("test_case_run")
public class TestCaseRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String modelName;
    private String judgeModel;
    private Integer total;
    private Integer passed;
    private BigDecimal avgScore;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
