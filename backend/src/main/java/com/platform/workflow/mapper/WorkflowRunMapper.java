package com.platform.workflow.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.workflow.entity.WorkflowRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    /** 自动恢复跨租户扫描；由调用方显式设置租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM workflow_run
            WHERE status = 'FAILED' AND current_node IS NOT NULL AND current_node <> ''
            ORDER BY updated_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<WorkflowRun> findFailedForAutoResume(@Param("limit") int limit);
}
