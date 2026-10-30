package com.platform.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书大文件夹异步导入任务。
 *
 * <p>同步接口仍保留；本服务用于文件夹/大量链接文档场景，避免一次请求等待过久。
 * 任务状态当前保存在进程内，适合单实例部署；多副本部署时应替换为 Redis/任务表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuImportJobService {

    private static final int MAX_JOBS = 200;
    private static final String VIEW_PREFIX = "feishu:import:job:";
    private static final Duration VIEW_TTL = Duration.ofHours(2);
    private final FeishuDocumentService feishuDocumentService;
    private final KnowledgeService knowledgeService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    @Resource(name = "feishuTaskExecutor")
    private ThreadPoolTaskExecutor executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private static final class Job {
        private final String id;
        private final Long kbId;
        private final Long tenantId;
        private final Long ownerId;
        private final Instant createdAt;
        private volatile String status = "QUEUED";
        private volatile int total;
        private volatile int imported;
        private volatile int failed;
        private volatile int skipped;
        private volatile String message = "";
        private final List<Map<String, Object>> documents = Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String, Object>> failures = Collections.synchronizedList(new ArrayList<>());
        private final List<String> skippedItems = Collections.synchronizedList(new ArrayList<>());

        private Job(String id, Long kbId, Long tenantId, Long ownerId) {
            this.id = id;
            this.kbId = kbId;
            this.tenantId = tenantId;
            this.ownerId = ownerId;
            this.createdAt = Instant.now();
        }

        private Map<String, Object> view() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", id);
            body.put("kbId", kbId);
            body.put("ownerId", ownerId);
            body.put("status", status);
            body.put("total", total);
            body.put("imported", imported);
            body.put("failed", failed);
            body.put("skipped", skipped);
            body.put("message", message);
            synchronized (documents) {
                body.put("documents", List.copyOf(documents));
            }
            synchronized (failures) {
                body.put("failures", List.copyOf(failures));
            }
            synchronized (skippedItems) {
                body.put("skippedItems", List.copyOf(skippedItems));
            }
            return body;
        }
    }

    public String start(Long kbId, String url, String appId, String appSecret,
                        Instant effectiveFrom, Instant effectiveTo,
                        KnowledgePermissionService.Viewer viewer, Long tenantId) {
        if (tenantId == null) throw new BusinessException(400, "租户上下文缺失");
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, kbId, tenantId, viewer.userId());
        jobs.put(id, job);
        save(job);
        prune();
        executor.execute(() -> TenantContext.run(tenantId,
                () -> run(job, url, appId, appSecret, effectiveFrom, effectiveTo, viewer)));
        return id;
    }

    public Map<String, Object> status(Long kbId, String jobId, KnowledgePermissionService.Viewer viewer) {
        Job job = jobs.get(jobId);
        if (job != null) {
            if (!Objects.equals(job.kbId, kbId)) {
                throw new BusinessException(404, "飞书导入任务不存在或已过期");
            }
            if (!Objects.equals(job.ownerId, viewer.userId())) {
                throw new BusinessException(403, "无权查看该飞书导入任务");
            }
            return job.view();
        }
        // 其他副本提交/执行的任务通过 Redis 共享状态读取。
        try {
            String json = redis.opsForValue().get(VIEW_PREFIX + jobId);
            if (json == null || json.isBlank()) {
                throw new BusinessException(404, "飞书导入任务不存在或已过期");
            }
            Map<String, Object> view = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() { });
            if (!String.valueOf(kbId).equals(String.valueOf(view.get("kbId")))) {
                throw new BusinessException(404, "飞书导入任务不存在或已过期");
            }
            if (!String.valueOf(viewer.userId()).equals(String.valueOf(view.get("ownerId")))) {
                throw new BusinessException(403, "无权查看该飞书导入任务");
            }
            return view;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.debug("飞书导入任务状态读取失败 job={} err={}", jobId, e.getMessage());
            throw new BusinessException(404, "飞书导入任务不存在或已过期");
        }
    }

    private void run(Job job, String url, String appId, String appSecret,
                     Instant effectiveFrom, Instant effectiveTo,
                     KnowledgePermissionService.Viewer viewer) {
        job.status = "RUNNING";
        job.message = "正在读取飞书文档列表";
        save(job);
        try {
            FeishuDocumentService.Collection collection =
                    feishuDocumentService.collectDetailed(url, appId, appSecret);
            job.total = collection.documents().size();
            job.skippedItems.addAll(collection.skipped());
            job.skipped = collection.skipped().size();
            job.message = "正在导入文档";
            save(job);
            for (FeishuDocumentService.Document document : collection.documents()) {
                try {
                    Map<String, Object> imported = knowledgeService.importContent(job.kbId,
                            document.name(), document.content(), document.contentType(),
                            effectiveFrom, effectiveTo, viewer, document.sourceUrl());
                    synchronized (job.documents) {
                        job.documents.add(imported);
                    }
                    job.imported++;
                } catch (Exception e) {
                    String error = e.getMessage() == null ? "导入失败" : e.getMessage();
                    job.failures.add(Map.of("name", document.name(), "error", error));
                    job.failed++;
                }
                save(job);
            }
            job.status = "DONE";
            job.message = job.failed > 0 ? "部分文档导入失败" : "导入完成";
            save(job);
            log.info("飞书异步导入完成 job={} kb={} total={} imported={} failed={} skipped={}",
                    job.id, job.kbId, job.total, job.imported, job.failed, job.skipped);
        } catch (Exception e) {
            job.status = "FAILED";
            job.message = e.getMessage() == null ? "导入失败" : e.getMessage();
            save(job);
            log.warn("飞书异步导入失败 job={} kb={} err={}", job.id, job.kbId, job.message);
        }
    }

    private void save(Job job) {
        try {
            redis.opsForValue().set(VIEW_PREFIX + job.id,
                    objectMapper.writeValueAsString(job.view()), VIEW_TTL);
        } catch (Exception e) {
            log.debug("飞书导入任务状态写入失败 job={} err={}", job.id, e.getMessage());
        }
    }

    private void prune() {
        if (jobs.size() <= MAX_JOBS) return;
        jobs.values().stream()
                .min(Comparator.comparing(j -> j.createdAt))
                .ifPresent(oldest -> jobs.remove(oldest.id));
    }
}
