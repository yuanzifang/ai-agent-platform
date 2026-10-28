package com.platform.common.checkpoint;

import com.platform.conversation.mapper.ChatCheckpointMapper;
import com.platform.workflow.mapper.WorkflowCheckpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 定期清理过期 checkpoint；工作流每个 run 至少保留最新一条。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckpointRetentionScheduler {

    private final WorkflowCheckpointMapper workflowCheckpointMapper;
    private final ChatCheckpointMapper chatCheckpointMapper;

    @Value("${checkpoint.retention.enabled:true}")
    private boolean enabled = true;

    @Value("${checkpoint.retention.days:30}")
    private int retentionDays = 30;

    @Value("${checkpoint.retention.batch:1000}")
    private int batch = 1000;

    @Scheduled(fixedDelayString = "${checkpoint.retention.scan-ms:21600000}")
    public void cleanup() {
        if (!enabled) return;
        int days = Math.max(1, Math.min(retentionDays, 3650));
        int limit = Math.max(1, Math.min(batch, 10000));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        try {
            int workflow = workflowCheckpointMapper.deleteOldKeepLatest(cutoff, limit);
            int chat = chatCheckpointMapper.deleteOld(cutoff, limit);
            if (workflow > 0 || chat > 0) {
                log.info("Checkpoint 清理完成 workflow={} chat={} cutoff={}", workflow, chat, cutoff);
            }
        } catch (Exception e) {
            log.warn("Checkpoint 清理失败：{}", e.getMessage());
        }
    }
}
