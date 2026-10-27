package com.platform.knowledge.service;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.platform.knowledge.mapper.KnowledgeOcrCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** OCR 持久化缓存按最后使用时间清理，避免历史图像文本无限增长。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeOcrCacheScheduler {

    private final KnowledgeOcrCacheMapper cacheMapper;

    @Value("${knowledge.ocr.cache-retention-days:30}")
    private int retentionDays = 30;

    @Scheduled(cron = "${knowledge.ocr.cache-cleanup-cron:0 45 4 * * *}")
    public void cleanup() {
        try {
            int days = Math.max(1, retentionDays);
            int deleted = cacheMapper.deleteOlderThan(LocalDateTime.now().minusDays(days), 10000);
            if (deleted > 0) log.info("OCR 缓存清理：deleted={} retentionDays={}", deleted, days);
        } catch (Exception e) {
            log.warn("OCR 缓存清理失败：{}", e.getMessage());
        }
    }
}
