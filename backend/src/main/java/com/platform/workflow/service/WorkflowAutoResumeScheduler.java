package com.platform.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.security.JwtPrincipal;
import com.platform.workflow.entity.WorkflowCheckpoint;
import com.platform.workflow.entity.WorkflowRun;
import com.platform.workflow.mapper.WorkflowCheckpointMapper;
import com.platform.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可选自动恢复：默认关闭；只恢复非副作用节点，失败次数超过上限则停止。
 * 需要显式设置 WORKFLOW_AUTO_RESUME_ENABLED=true。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowAutoResumeScheduler {

    private final WorkflowService workflowService;
    private final WorkflowRunMapper runMapper;
    private final WorkflowCheckpointMapper checkpointMapper;

    @Value("${workflow.checkpoint.auto-resume.enabled:false}")
    private boolean enabled = false;

    @Value("${workflow.checkpoint.auto-resume.max-attempts:2}")
    private int maxAttempts = 2;

    @Value("${workflow.checkpoint.auto-resume.batch:5}")
    private int batch = 5;

    @Scheduled(fixedDelayString = "${workflow.checkpoint.auto-resume.scan-ms:60000}")
    public void scan() {
        if (!enabled) return;
        List<WorkflowRun> runs = runMapper.findFailedForAutoResume(Math.max(1, Math.min(batch, 20)));
        for (WorkflowRun run : runs) {
            try {
                long failedCheckpoints = checkpointMapper.selectCount(
                        new LambdaQueryWrapper<WorkflowCheckpoint>()
                                .eq(WorkflowCheckpoint::getRunId, run.getId())
                                .eq(WorkflowCheckpoint::getStatus, "FAILED"));
                if (failedCheckpoints > Math.max(0, maxAttempts)) {
                    log.warn("工作流自动恢复跳过：失败次数超限 run={} failures={}", run.getId(), failedCheckpoints);
                    continue;
                }
                JwtPrincipal system = new JwtPrincipal(0L, run.getTenantId(), true,
                        List.of("OWNER"), List.of());
                TenantContext.runWith(run.getTenantId(), () ->
                        workflowService.resume(run.getId(), system, false));
                log.info("工作流自动恢复已触发 run={}", run.getId());
            } catch (Exception e) {
                log.warn("工作流自动恢复失败 run={}：{}", run.getId(), e.getMessage());
            }
        }
    }
}
