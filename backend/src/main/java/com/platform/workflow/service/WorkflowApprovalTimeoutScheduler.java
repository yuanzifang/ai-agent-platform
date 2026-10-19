package com.platform.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 审批超时扫描：默认每分钟检查一次，超时审批置 EXPIRED 并终止运行。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowApprovalTimeoutScheduler {

    private final WorkflowService workflowService;

    @Scheduled(fixedDelayString = "${workflow.approval.scan-ms:60000}")
    public void scan() {
        try {
            int expired = workflowService.scanExpiredApprovals();
            if (expired > 0) log.info("审批超时扫描：处理 {} 条", expired);
        } catch (Exception e) {
            log.warn("审批超时扫描失败：{}", e.getMessage());
        }
    }
}
