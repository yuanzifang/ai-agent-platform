package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeUpgradeJob;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeUpgradeJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存量文档升级队列：MySQL 持久化任务和文档状态，后台按租户分批推进。
 * 任务只负责把文档提交给现有异步解析器；实际解析状态仍以 knowledge_document 为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeUpgradeService {

    private final KnowledgeUpgradeJobMapper jobMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeService knowledgeService;
    private final KnowledgePermissionService permissionService;

    @Value("${knowledge.upgrade.batch:10}")
    private int upgradeBatch = 10;

    /** 创建任务并把当前所有待升级 DONE 文档挂到任务下；已有 RUNNING 任务则直接返回。 */
    public Map<String, Object> start(Long kbId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BusinessException(403, "缺少租户上下文");
        KnowledgeUpgradeJob active = jobMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeUpgradeJob>()
                        .eq(KnowledgeUpgradeJob::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeUpgradeJob::getStatus, "RUNNING")
                        .orderByDesc(KnowledgeUpgradeJob::getCreatedAt)
                        .last("LIMIT 1"));
        if (active != null) return view(active);

        KnowledgeUpgradeJob job = new KnowledgeUpgradeJob();
        job.setTenantId(tenantId);
        job.setKnowledgeBaseId(kbId);
        job.setStatus("RUNNING");
        job.setTotalDocuments(0);
        job.setSubmittedDocuments(0);
        job.setSucceededDocuments(0);
        job.setFailedDocuments(0);
        job.setCreatedBy(viewer.userId());
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.insert(job);

        int assigned = docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                .eq(KnowledgeDocument::getStatus, "DONE")
                .isNull(KnowledgeDocument::getParseFingerprint)
                .set(KnowledgeDocument::getUpgradeJobId, job.getId())
                .set(KnowledgeDocument::getUpgradeStatus, "PENDING"));
        job.setTotalDocuments(assigned);
        if (assigned == 0) job.setStatus("COMPLETED");
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        log.info("创建存量升级任务：kb={} job={} total={}", kbId, job.getId(), assigned);
        return view(job);
    }

    /** 最近一次升级任务；没有任务时返回空对象。 */
    public Map<String, Object> latest(Long kbId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        KnowledgeUpgradeJob job = jobMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeUpgradeJob>()
                        .eq(KnowledgeUpgradeJob::getKnowledgeBaseId, kbId)
                        .orderByDesc(KnowledgeUpgradeJob::getCreatedAt)
                        .last("LIMIT 1"));
        return job == null ? Map.of() : view(job);
    }

    /** 指定任务进度。 */
    public Map<String, Object> status(Long kbId, Long jobId,
                                      KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        KnowledgeUpgradeJob job = jobMapper.selectById(jobId);
        if (job == null || !kbId.equals(job.getKnowledgeBaseId())) {
            throw new BusinessException(404, "升级任务不存在");
        }
        return view(job);
    }

    /** 取消运行中的任务；已提交给解析器的文档不会强行中断。 */
    public Map<String, Object> cancel(Long kbId, Long jobId,
                                      KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeUpgradeJob job = jobMapper.selectById(jobId);
        if (job == null || !kbId.equals(job.getKnowledgeBaseId())) {
            throw new BusinessException(404, "升级任务不存在");
        }
        if (!"RUNNING".equals(job.getStatus())) {
            throw new BusinessException(400, "仅运行中的升级任务可取消");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<KnowledgeUpgradeJob>()
                .eq(KnowledgeUpgradeJob::getId, jobId)
                .eq(KnowledgeUpgradeJob::getStatus, "RUNNING")
                .set(KnowledgeUpgradeJob::getStatus, "CANCELLED")
                .set(KnowledgeUpgradeJob::getUpdatedAt, LocalDateTime.now()));
        job.setStatus("CANCELLED");
        log.info("取消存量升级任务：kb={} job={}", kbId, jobId);
        return view(job);
    }

    /** 定时任务调用：当前租户下推进若干任务。 */
    public int processTenant(int batch) {
        int max = Math.max(1, Math.min(batch, 50));
        List<KnowledgeUpgradeJob> jobs = jobMapper.selectList(
                new LambdaQueryWrapper<KnowledgeUpgradeJob>()
                        .eq(KnowledgeUpgradeJob::getStatus, "RUNNING")
                        .orderByAsc(KnowledgeUpgradeJob::getCreatedAt)
                        .last("LIMIT 5"));
        int submitted = 0;
        for (KnowledgeUpgradeJob job : jobs) {
            try {
                submitted += processJob(job, max);
            } catch (Exception e) {
                log.warn("升级任务推进失败 job={} err={}", job.getId(), e.getMessage());
                job.setLastError(e.getMessage());
                job.setUpdatedAt(LocalDateTime.now());
                jobMapper.updateById(job);
            }
        }
        return submitted;
    }

    private int processJob(KnowledgeUpgradeJob job, int batch) {
        reconcile(job.getId());
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getUpgradeJobId, job.getId())
                        .eq(KnowledgeDocument::getUpgradeStatus, "PENDING")
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNull(KnowledgeDocument::getParseFingerprint)
                        .orderByAsc(KnowledgeDocument::getId)
                        .last("LIMIT " + batch));
        int submitted = 0;
        if (docs != null) {
            for (KnowledgeDocument doc : docs) {
                int claimed = docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getId, doc.getId())
                        .eq(KnowledgeDocument::getUpgradeStatus, "PENDING")
                        .set(KnowledgeDocument::getUpgradeStatus, "RUNNING"));
                if (claimed == 0) continue;
                try {
                    if (knowledgeService.reparseForUpgrade(job.getKnowledgeBaseId(), doc.getId())) {
                        submitted++;
                    } else {
                        markFailed(doc.getId(), job.getId(), "文档状态已变化");
                    }
                } catch (Exception e) {
                    markFailed(doc.getId(), job.getId(), e.getMessage());
                    job.setLastError(e.getMessage());
                }
            }
        }
        refresh(job);
        return submitted;
    }

    private void reconcile(Long jobId) {
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUpgradeJobId, jobId)
                .eq(KnowledgeDocument::getStatus, "DONE")
                .isNotNull(KnowledgeDocument::getParseFingerprint)
                .ne(KnowledgeDocument::getUpgradeStatus, "SUCCEEDED")
                .set(KnowledgeDocument::getUpgradeStatus, "SUCCEEDED"));
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUpgradeJobId, jobId)
                .eq(KnowledgeDocument::getStatus, "FAILED")
                .ne(KnowledgeDocument::getUpgradeStatus, "FAILED")
                .set(KnowledgeDocument::getUpgradeStatus, "FAILED"));
        // 提交后仍未产生指纹、又回到 DONE 的文档，回到 PENDING 由下一轮重试。
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUpgradeJobId, jobId)
                .eq(KnowledgeDocument::getStatus, "DONE")
                .eq(KnowledgeDocument::getUpgradeStatus, "RUNNING")
                .isNull(KnowledgeDocument::getParseFingerprint)
                .set(KnowledgeDocument::getUpgradeStatus, "PENDING"));
    }

    private int count(Long jobId, String status) {
        Long count = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUpgradeJobId, jobId)
                .eq(KnowledgeDocument::getUpgradeStatus, status));
        return count == null ? 0 : count.intValue();
    }

    private void refresh(KnowledgeUpgradeJob job) {
        int pending = count(job.getId(), "PENDING");
        int running = count(job.getId(), "RUNNING");
        int succeeded = count(job.getId(), "SUCCEEDED");
        int failed = count(job.getId(), "FAILED");
        int total = job.getTotalDocuments() == null ? 0 : job.getTotalDocuments();
        job.setSucceededDocuments(succeeded);
        job.setFailedDocuments(failed);
        job.setSubmittedDocuments(Math.max(0, Math.min(total, total - pending)));
        if ("RUNNING".equals(job.getStatus()) && pending == 0 && running == 0) {
            job.setStatus(succeeded == 0 && failed > 0 ? "FAILED" : "COMPLETED");
        }
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private void markFailed(Long documentId, Long jobId, String error) {
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getUpgradeJobId, jobId)
                .set(KnowledgeDocument::getUpgradeStatus, "FAILED")
                .set(KnowledgeDocument::getErrorMessage, error));
    }

    private Map<String, Object> view(KnowledgeUpgradeJob job) {
        refresh(job);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jobId", job.getId());
        row.put("status", job.getStatus());
        row.put("totalDocuments", job.getTotalDocuments());
        row.put("submittedDocuments", job.getSubmittedDocuments());
        row.put("succeededDocuments", job.getSucceededDocuments());
        row.put("failedDocuments", job.getFailedDocuments());
        row.put("pendingDocuments", Math.max(0,
                (job.getTotalDocuments() == null ? 0 : job.getTotalDocuments())
                        - (job.getSubmittedDocuments() == null ? 0 : job.getSubmittedDocuments())));
        row.put("lastError", job.getLastError());
        row.put("createdAt", job.getCreatedAt());
        row.put("updatedAt", job.getUpdatedAt());
        return row;
    }
}
