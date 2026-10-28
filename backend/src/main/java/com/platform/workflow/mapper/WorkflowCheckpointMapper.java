package com.platform.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.workflow.entity.WorkflowCheckpoint;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowCheckpointMapper extends BaseMapper<WorkflowCheckpoint> {
}
