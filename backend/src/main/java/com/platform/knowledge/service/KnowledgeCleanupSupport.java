package com.platform.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 外部对象存储/向量清理的事务后置执行工具。 */
@Slf4j
public final class KnowledgeCleanupSupport {

    private KnowledgeCleanupSupport() {
    }

    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
        } else {
            runQuietly(action);
        }
    }

    public static void removeObjectQuietly(StorageService storage, String objectName) {
        if (objectName == null || objectName.isBlank()) return;
        try {
            storage.remove(objectName);
        } catch (Exception e) {
            log.warn("删除对象存储失败 object={} err={}", objectName, e.getMessage());
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("事务提交后清理失败：{}", e.getMessage());
        }
    }
}
