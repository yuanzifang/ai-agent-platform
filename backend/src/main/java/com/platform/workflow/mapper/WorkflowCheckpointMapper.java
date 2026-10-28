package com.platform.workflow.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.workflow.entity.WorkflowCheckpoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowCheckpointMapper extends BaseMapper<WorkflowCheckpoint> {

    /** 跨租户清理旧 checkpoint，但每个 run 永远保留最新一条。 */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
            DELETE FROM workflow_checkpoint
            WHERE created_at < #{cutoff}
              AND id NOT IN (
                SELECT keep_id FROM (
                  SELECT MAX(id) AS keep_id FROM workflow_checkpoint GROUP BY run_id
                ) keep_rows
              )
            LIMIT #{batch}
            """)
    int deleteOldKeepLatest(@Param("cutoff") java.time.LocalDateTime cutoff,
                            @Param("batch") int batch);
}
