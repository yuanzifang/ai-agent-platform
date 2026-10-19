package com.platform.workflow.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.workflow.entity.WorkflowApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkflowApprovalMapper extends BaseMapper<WorkflowApproval> {

    /** 超时扫描：跨租户任务，绕过租户插件，由调度器自行按租户处理。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM workflow_approval
            WHERE status = 'PENDING' AND expire_at IS NOT NULL AND expire_at <= #{now}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<WorkflowApproval> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
