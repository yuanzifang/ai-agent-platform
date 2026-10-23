package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("test_case_run_item")
public class TestCaseRunItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long caseId;
    private String question;
    private String answer;
    private BigDecimal score;
    private BigDecimal humanScore;
    private String humanLabel;
    private Long reviewerId;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    private Integer labelCount;
    private Integer sampled;
    private Long assigneeId;
    private Integer passed;
    private Long latencyMs;
    private String error;
    private LocalDateTime createdAt;
}
