package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeGraphJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeGraphJobMapper extends BaseMapper<KnowledgeGraphJob> {

    /** 启动恢复：单实例部署下，上次进程退出时遗留的 PENDING/RUNNING 任务不会再有线程推进。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE knowledge_graph_job SET status = 'FAILED', "
            + "error = '服务重启，任务中断，请重新构建', finished_at = NOW() "
            + "WHERE status IN ('PENDING', 'RUNNING')")
    int failInterruptedJobs();
}
