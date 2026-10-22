package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.entity.TestCase;
import com.platform.agent.entity.TestCaseRun;
import com.platform.agent.entity.CaseCalibrationLabel;
import com.platform.agent.entity.TestCaseRunItem;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.agent.mapper.TestCaseMapper;
import com.platform.agent.mapper.CaseCalibrationLabelMapper;
import com.platform.agent.mapper.TestCaseRunItemMapper;
import com.platform.agent.mapper.TestCaseRunMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.entity.Message;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.mapper.MessageMapper;
import com.platform.eval.service.EvalService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.model.service.ChatModelRouter;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话用例库：点踩/点赞自动沉淀候选，人工审核；支持导出评测集与候选模型回归。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseService {

    private static final int MAX_CASES = 500;
    private static final int MAX_RUN_CASES = 20;

    @org.springframework.beans.factory.annotation.Value("${case.calibration.min-annotators:2}")
    private int minAnnotators;

    private final TestCaseMapper caseMapper;
    private final TestCaseRunMapper runMapper;
    private final TestCaseRunItemMapper runItemMapper;
    private final CaseCalibrationLabelMapper calibrationMapper;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentRegistryMapper agentMapper;
    private final ChatService chatService;
    private final ChatModelRouter chatModelRouter;
    private final EvalService evalService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    @Qualifier("llmTaskExecutor")
    private final org.springframework.core.task.TaskExecutor llmExecutor;

    /** 反馈回调：UP→经典候选，DOWN→坏例候选；同一消息只保留一条，重复反馈更新类型。 */
    public void captureFeedback(Long conversationId, Long assistantMessageId, String type,
                                String comment, JwtPrincipal principal) {
        try {
            Message ai = messageMapper.selectById(assistantMessageId);
            if (ai == null || !"ASSISTANT".equals(ai.getRole())) return;
            Conversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) return;
            TestCase existing = caseMapper.selectOne(new LambdaQueryWrapper<TestCase>()
                    .eq(TestCase::getAssistantMessageId, assistantMessageId).last("LIMIT 1"));
            if (existing != null) {
                existing.setKind("UP".equals(type) ? "CLASSIC" : "BAD");
                existing.setSource("UP".equals(type) ? "AUTO_CLASSIC" : "AUTO_BAD");
                existing.setStatus("CANDIDATE");
                existing.setNote(comment);
                existing.setUpdatedAt(LocalDateTime.now());
                caseMapper.updateById(existing);
                return;
            }
            Message user = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .eq(Message::getRole, "USER")
                            .lt(Message::getId, assistantMessageId)
                            .orderByDesc(Message::getId).last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            TestCase c = new TestCase();
            c.setTenantId(principal.tenantId());
            c.setAgentId(conv.getAgentId());
            c.setConversationId(conversationId);
            c.setUserMessageId(user == null ? null : user.getId());
            c.setAssistantMessageId(assistantMessageId);
            c.setQuestion(user == null ? "" : user.getContent());
            c.setActualAnswer(ai.getContent());
            c.setKind("UP".equals(type) ? "CLASSIC" : "BAD");
            c.setSource("UP".equals(type) ? "AUTO_CLASSIC" : "AUTO_BAD");
            c.setStatus("CANDIDATE");
            c.setNote(comment);
            c.setTraceId(ai.getTraceId());
            c.setCreatedBy(principal.userId());
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            caseMapper.insert(c);
            log.info("用例库沉淀：kind={} message={}", c.getKind(), assistantMessageId);
        } catch (Exception e) {
            log.warn("用例库自动沉淀失败（不影响反馈）：{}", e.getMessage());
        }
    }

    /** 通用自动沉淀：ERROR/BLOCKED/自我纠正/重新生成等。 */
    public void captureAuto(Long tenantId, Long agentId, Long conversationId,
                            Long userMessageId, Long assistantMessageId, String question,
                            String actualAnswer, String expectedAnswer, String kind,
                            String source, String note, String traceId, Long createdBy) {
        try {
            if (assistantMessageId == null) return;
            TestCase existing = caseMapper.selectOne(new LambdaQueryWrapper<TestCase>()
                    .eq(TestCase::getAssistantMessageId, assistantMessageId).last("LIMIT 1"));
            if (existing != null) {
                // 已有人工收藏/反馈时，不用自动状态覆盖审核结果
                if (existing.getSource() != null && existing.getSource().startsWith("AUTO_")
                        && "CANDIDATE".equals(existing.getStatus())) {
                    existing.setSource(source);
                    existing.setKind(kind);
                    existing.setNote(note);
                    if (expectedAnswer != null && !expectedAnswer.isBlank()) {
                        existing.setExpectedAnswer(expectedAnswer);
                    }
                    existing.setUpdatedAt(LocalDateTime.now());
                    caseMapper.updateById(existing);
                }
                return;
            }
            long count = caseMapper.selectCount(new LambdaQueryWrapper<TestCase>()
                    .eq(TestCase::getTenantId, tenantId));
            if (count >= MAX_CASES) return;
            TestCase c = new TestCase();
            c.setTenantId(tenantId);
            c.setAgentId(agentId);
            c.setConversationId(conversationId);
            c.setUserMessageId(userMessageId);
            c.setAssistantMessageId(assistantMessageId);
            c.setQuestion(question == null ? "" : question);
            c.setActualAnswer(actualAnswer);
            c.setExpectedAnswer(expectedAnswer);
            c.setKind(kind == null ? "BAD" : kind);
            c.setSource(source);
            c.setStatus("CANDIDATE");
            c.setNote(note);
            c.setTraceId(traceId);
            c.setCreatedBy(createdBy);
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            caseMapper.insert(c);
            log.info("自动沉淀用例：source={} message={}", source, assistantMessageId);
        } catch (Exception e) {
            log.warn("自动沉淀用例失败（忽略）：{}", e.getMessage());
        }
    }

    public void archiveAuto(Long assistantMessageId) {
        try {
            TestCase c = caseMapper.selectOne(new LambdaQueryWrapper<TestCase>()
                    .eq(TestCase::getAssistantMessageId, assistantMessageId).last("LIMIT 1"));
            if (c != null && c.getSource() != null && c.getSource().startsWith("AUTO_")) {
                c.setStatus("ARCHIVED");
                c.setUpdatedAt(LocalDateTime.now());
                caseMapper.updateById(c);
            }
        } catch (Exception e) {
            log.debug("用例归档失败：{}", e.getMessage());
        }
    }

    public Map<String, Object> list(JwtPrincipal principal, String status, String source,
                                    Long agentId, String keyword, String tier) {
        List<TestCase> rows = caseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getTenantId, principal.tenantId())
                .eq(status != null && !status.isBlank(), TestCase::getStatus, status)
                .eq(tier != null && !tier.isBlank(), TestCase::getDatasetTier, tier)
                .eq(source != null && !source.isBlank(), TestCase::getSource, source)
                .eq(agentId != null, TestCase::getAgentId, agentId)
                .like(keyword != null && !keyword.isBlank(), TestCase::getQuestion, keyword)
                .orderByDesc(TestCase::getId)
                .last("LIMIT 500"));
        List<Map<String, Object>> list = rows.stream().map(this::view).toList();
        return Map.of("list", list, "total", list.size());
    }

    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Long messageId = toLong(req.get("assistantMessageId"));
        if (messageId == null) throw new BusinessException(400, "缺少 assistantMessageId");
        Message ai = messageMapper.selectById(messageId);
        if (ai == null || !"ASSISTANT".equals(ai.getRole())) {
            throw new BusinessException(404, "AI 消息不存在");
        }
        Conversation conv = conversationMapper.selectById(ai.getConversationId());
        if (conv == null || !principal.tenantId().equals(conv.getTenantId())) {
            throw new BusinessException(403, "无权收藏该消息");
        }
        String question = str(req.get("question"));
        if (question == null || question.isBlank()) {
            Message user = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, ai.getConversationId())
                            .eq(Message::getRole, "USER")
                            .lt(Message::getId, messageId)
                            .orderByDesc(Message::getId).last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            question = user == null ? "" : user.getContent();
        }
        TestCase c = caseMapper.selectOne(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getAssistantMessageId, messageId).last("LIMIT 1"));
        boolean isNew = c == null;
        if (isNew) {
            long count = caseMapper.selectCount(new LambdaQueryWrapper<TestCase>()
                    .eq(TestCase::getTenantId, principal.tenantId()));
            if (count >= MAX_CASES) throw new BusinessException(400, "用例库已达上限 " + MAX_CASES);
            c = new TestCase();
            c.setTenantId(principal.tenantId());
            c.setAgentId(conv.getAgentId());
            c.setConversationId(conv.getId());
            c.setAssistantMessageId(messageId);
            c.setActualAnswer(ai.getContent());
            c.setTraceId(ai.getTraceId());
            c.setSource("MANUAL");
            c.setCreatedBy(principal.userId());
            c.setCreatedAt(LocalDateTime.now());
        }
        c.setQuestion(question);
        String kind = str(req.getOrDefault("kind", "BAD")).toUpperCase();
        c.setKind("CLASSIC".equals(kind) ? "CLASSIC" : "BAD");
        String expected = str(req.get("expectedAnswer"));
        if (expected != null && !expected.isBlank()) c.setExpectedAnswer(expected);
        c.setNote(str(req.get("note")));
        c.setTags(tagsJson(req.get("tags")));
        c.setStatus("CANDIDATE");
        c.setDatasetTier("CANDIDATE");
        c.setRiskLevel(str(req.get("riskLevel")));
        c.setCoverageTags(tagsJson(req.get("coverageTags")));
        c.setExpiresAt(dateTime(req.get("expiresAt")));
        c.setUpdatedAt(LocalDateTime.now());
        if (isNew) caseMapper.insert(c); else caseMapper.updateById(c);
        auditService.record(principal.userId(), "case.create", "test_case", String.valueOf(c.getId()),
                "SUCCESS", Map.of("kind", c.getKind()));
        return view(c);
    }

    public Map<String, Object> update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        TestCase c = owned(id, principal);
        if (req.get("status") != null) {
            String status = str(req.get("status")).toUpperCase();
            if (!List.of("CANDIDATE", "APPROVED", "ARCHIVED").contains(status)) {
                throw new BusinessException(400, "非法状态");
            }
            c.setStatus(status);
        }
        if (req.get("expectedAnswer") != null) c.setExpectedAnswer(str(req.get("expectedAnswer")));
        if (req.get("note") != null) c.setNote(str(req.get("note")));
        if (req.get("tags") != null) c.setTags(tagsJson(req.get("tags")));
        if (req.get("kind") != null) {
            c.setKind("CLASSIC".equalsIgnoreCase(str(req.get("kind"))) ? "CLASSIC" : "BAD");
        }
        if (req.get("datasetTier") != null) {
            String tier = str(req.get("datasetTier")).toUpperCase();
            if (!List.of("CANDIDATE", "APPROVED", "GOLDEN", "ARCHIVED").contains(tier)) {
                throw new BusinessException(400, "非法数据集层级");
            }
            c.setDatasetTier(tier);
        }
        if (req.get("riskLevel") != null) c.setRiskLevel(str(req.get("riskLevel")));
        if (req.get("coverageTags") != null) c.setCoverageTags(tagsJson(req.get("coverageTags")));
        if (req.get("expiresAt") != null) c.setExpiresAt(dateTime(req.get("expiresAt")));
        if (c.getDatasetTier() == null) c.setDatasetTier("CANDIDATE");
        if ("CANDIDATE".equals(c.getStatus()) && !"GOLDEN".equals(c.getDatasetTier())) {
            c.setDatasetTier("CANDIDATE");
        }
        if ("ARCHIVED".equals(c.getStatus())) c.setDatasetTier("ARCHIVED");
        if ("APPROVED".equals(c.getStatus()) && "CANDIDATE".equals(c.getDatasetTier())) {
            c.setDatasetTier("APPROVED");
        }
        if ("GOLDEN".equals(c.getDatasetTier())) {
            if (!"APPROVED".equals(c.getStatus())) {
                throw new BusinessException(400, "只有已审核用例才能晋升黄金集");
            }
            if (c.getRiskLevel() == null || c.getRiskLevel().isBlank()) {
                throw new BusinessException(400, "黄金用例必须设置风险等级");
            }
            if (c.getCoverageTags() == null || c.getCoverageTags().isBlank()
                    || "[]".equals(c.getCoverageTags().replace(" ", ""))) {
                throw new BusinessException(400, "黄金用例必须设置覆盖标签");
            }
            if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new BusinessException(400, "黄金用例已过期，请更新有效期");
            }
        }
        if ("APPROVED".equals(c.getStatus()) && "BAD".equals(c.getKind())
                && (c.getExpectedAnswer() == null || c.getExpectedAnswer().isBlank())) {
            throw new BusinessException(400, "坏例审核通过前必须填写正确的期望答案");
        }
        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);
        return view(c);
    }

    public void delete(Long id, JwtPrincipal principal) {
        TestCase c = owned(id, principal);
        caseMapper.deleteById(c.getId());
    }

    /** 将审核通过的用例导出为评测数据集（复用现有 Eval 数据模型）。 */
    public Map<String, Object> export(Map<String, Object> req, JwtPrincipal principal) {
        List<TestCase> cases = selectForRun(req.get("caseIds"), principal, str(req.get("tier")));
        if (cases.isEmpty()) throw new BusinessException(400, "没有可导出的已审核用例");
        String name = str(req.get("name"));
        if (name == null || name.isBlank()) name = "用例库导出-" + LocalDateTime.now();
        Long agentId = cases.stream().map(TestCase::getAgentId).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        Map<String, Object> dataset = evalService.createDataset(name, "由对话用例库导出", agentId, principal);
        Long datasetId = Long.valueOf(String.valueOf(dataset.get("id")));
        for (TestCase c : cases) {
            String expected = c.getExpectedAnswer() != null && !c.getExpectedAnswer().isBlank()
                    ? c.getExpectedAnswer() : c.getActualAnswer();
            evalService.addItem(datasetId, c.getQuestion(), expected,
                    "回答需切题、准确，与参考答案语义一致", principal);
        }
        auditService.record(principal.userId(), "case.export", "eval_dataset", String.valueOf(datasetId),
                "SUCCESS", Map.of("cases", cases.size()));
        return Map.of("datasetId", datasetId, "cases", cases.size());
    }

    /** 候选模型回归：用 Agent 已发布配置 + 候选模型逐条执行，LLM 裁判对比参考答案。 */
    public Map<String, Object> run(Map<String, Object> req, JwtPrincipal principal) {
        String modelName = str(req.get("modelName"));
        if (modelName == null || modelName.isBlank()) throw new BusinessException(400, "请选择候选模型");
        String judgeModel = str(req.getOrDefault("judgeModel",
                chatModelRouter.defaultModelName()));
        List<TestCase> cases = selectForRun(req.get("caseIds"), principal, str(req.get("tier")));
        if (cases.isEmpty()) throw new BusinessException(400, "没有可回归的已审核用例");
        if (cases.size() > MAX_RUN_CASES) cases = cases.subList(0, MAX_RUN_CASES);

        TestCaseRun run = new TestCaseRun();
        run.setTenantId(principal.tenantId());
        run.setName(str(req.getOrDefault("name", "模型回归-" + LocalDateTime.now())));
        run.setModelName(modelName);
        run.setJudgeModel(judgeModel);
        run.setTotal(cases.size());
        run.setPassed(0);
        run.setStatus("RUNNING");
        run.setCreatedBy(principal.userId());
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);

        boolean async = Boolean.TRUE.equals(req.get("async"))
                || "true".equals(String.valueOf(req.get("async")));
        if (async) {
            final List<TestCase> queued = cases;
            llmExecutor.execute(() -> com.platform.common.context.TenantContext.runWith(
                    principal.tenantId(), () -> {
                        executeRun(run, queued, modelName, judgeModel, principal);
                        return null;
                    }));
            return runView(run);
        }
        return executeRun(run, cases, modelName, judgeModel, principal);
    }

    private Map<String, Object> executeRun(TestCaseRun run, List<TestCase> cases, String modelName,
                                           String judgeModel, JwtPrincipal principal) {
        int passed = 0;
        double scoreSum = 0;
        int scored = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (TestCase c : cases) {
            long start = System.currentTimeMillis();
            TestCaseRunItem item = new TestCaseRunItem();
            item.setRunId(run.getId());
            item.setCaseId(c.getId());
            item.setQuestion(c.getQuestion());
            item.setCreatedAt(LocalDateTime.now());
            try {
                Map<String, Object> config = publishedConfig(c.getAgentId());
                if (config == null) throw new IllegalStateException("Agent 发布配置不存在");
                @SuppressWarnings("unchecked")
                Map<String, Object> modelConfig = config.get("modelConfig") instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
                modelConfig.put("modelName", modelName);
                modelConfig.remove("fallbackModelName");
                config.put("modelConfig", modelConfig);
                var viewer = KnowledgePermissionService.Viewer.of(principal.userId(), principal.roles());
                Map<String, Object> answerMap = chatService.debugChat(config, c.getQuestion(), viewer, principal);
                String answer = str(answerMap.get("answer"));
                item.setAnswer(answer);
                double score = judge(c.getQuestion(), reference(c), answer, judgeModel);
                item.setScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
                boolean ok = score >= 0.6;
                item.setPassed(ok ? 1 : 0);
                if (ok) passed++;
                scoreSum += score;
                scored++;
            } catch (Exception e) {
                item.setError(truncate(e.getMessage(), 900));
                item.setPassed(0);
            }
            item.setLatencyMs(System.currentTimeMillis() - start);
            runItemMapper.insert(item);
            results.add(runItemView(item));
        }
        run.setPassed(passed);
        run.setAvgScore(scored == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(scoreSum / scored).setScale(4, RoundingMode.HALF_UP));
        run.setStatus("DONE");
        runMapper.updateById(run);
        auditService.record(principal.userId(), "case.regression", "test_case_run",
                String.valueOf(run.getId()), "SUCCESS",
                Map.of("model", modelName, "total", cases.size(), "passed", passed));
        Map<String, Object> result = new LinkedHashMap<>(runView(run));
        result.put("items", results);
        return result;
    }

    /** 裁判校准队列：回归明细的人工标注/校准。 */
    public Map<String, Object> calibrations(JwtPrincipal principal, String status, Long runId,
                                            int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int current = Math.max(1, page);
        List<TestCaseRun> tenantRuns = runMapper.selectList(new LambdaQueryWrapper<TestCaseRun>()
                .eq(TestCaseRun::getTenantId, principal.tenantId())
                .orderByDesc(TestCaseRun::getId).last("LIMIT 200"));
        Map<Long, TestCaseRun> runMap = new LinkedHashMap<>();
        for (TestCaseRun r : tenantRuns) runMap.put(r.getId(), r);
        if (runId != null) {
            if (!runMap.containsKey(runId)) throw new BusinessException(404, "回归记录不存在");
            runMap.keySet().retainAll(List.of(runId));
        }
        if (runMap.isEmpty()) return Map.of("list", List.of(), "total", 0, "page", current, "pageSize", size);
        LambdaQueryWrapper<TestCaseRunItem> wrapper = new LambdaQueryWrapper<TestCaseRunItem>()
                .in(TestCaseRunItem::getRunId, runMap.keySet())
                .lt("PENDING".equalsIgnoreCase(status), TestCaseRunItem::getLabelCount, Math.max(1, minAnnotators))
                .ge("DONE".equalsIgnoreCase(status), TestCaseRunItem::getLabelCount, Math.max(1, minAnnotators))
                .orderByDesc(TestCaseRunItem::getId);
        if (!"ALL".equalsIgnoreCase(status) && !"PENDING".equalsIgnoreCase(status)
                && !"DONE".equalsIgnoreCase(status)) {
            wrapper.lt(TestCaseRunItem::getLabelCount, Math.max(1, minAnnotators));
        }
        long total = runItemMapper.selectCount(wrapper);
        wrapper.last("LIMIT " + ((current - 1) * size) + ", " + size);
        List<TestCaseRunItem> items = runItemMapper.selectList(wrapper);
        List<Map<String, Object>> rows = items.stream().map(i -> calibrationView(i, runMap.get(i.getRunId())))
                .toList();
        return Map.of("list", rows, "total", total, "page", current, "pageSize", size);
    }

    public Map<String, Object> calibrationStats(JwtPrincipal principal) {
        List<Long> runIds = runMapper.selectList(new LambdaQueryWrapper<TestCaseRun>()
                        .eq(TestCaseRun::getTenantId, principal.tenantId()))
                .stream().map(TestCaseRun::getId).toList();
        if (runIds.isEmpty()) {
            return Map.of("total", 0, "labeled", 0, "pending", 0, "agreementRate", 0,
                    "meanAbsError", 0);
        }
        List<TestCaseRunItem> items = runItemMapper.selectList(new LambdaQueryWrapper<TestCaseRunItem>()
                .in(TestCaseRunItem::getRunId, runIds));
        int labeled = 0, pending = 0, agree = 0;
        double errorSum = 0;
        int errorCount = 0;
        for (TestCaseRunItem i : items) {
            if (i.getLabelCount() == null || i.getLabelCount() < Math.max(1, minAnnotators)) {
                pending++;
                continue;
            }
            labeled++;
            boolean judgePass = i.getScore() != null
                    && i.getScore().compareTo(BigDecimal.valueOf(0.6)) >= 0;
            boolean humanPass = "PASS".equalsIgnoreCase(i.getHumanLabel());
            if (judgePass == humanPass) agree++;
            if (i.getHumanScore() != null && i.getScore() != null) {
                errorSum += Math.abs(i.getHumanScore().doubleValue() - i.getScore().doubleValue());
                errorCount++;
            }
        }
        return Map.of("total", items.size(), "labeled", labeled, "pending", pending,
                "agreementRate", labeled == 0 ? 0 : round4(agree / (double) labeled),
                "meanAbsError", errorCount == 0 ? 0 : round4(errorSum / errorCount));
    }

    public Map<String, Object> calibrate(Long itemId, Map<String, Object> req, JwtPrincipal principal) {
        TestCaseRunItem item = runItemMapper.selectById(itemId);
        if (item == null) throw new BusinessException(404, "回归明细不存在");
        TestCaseRun run = runMapper.selectById(item.getRunId());
        Long tenantId = principal.platformAdmin()
                ? TenantContext.getTenantId() : principal.tenantId();
        if (run == null || !run.getTenantId().equals(tenantId)) {
            throw new BusinessException(404, "回归记录不存在");
        }
        String label = str(req.get("humanLabel"));
        if (label == null || (!"PASS".equalsIgnoreCase(label) && !"FAIL".equalsIgnoreCase(label))) {
            throw new BusinessException(400, "humanLabel 必须是 PASS 或 FAIL");
        }
        BigDecimal score = decimal(req.get("humanScore"));
        if (score == null) score = "PASS".equalsIgnoreCase(label) ? BigDecimal.ONE : BigDecimal.ZERO;
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(400, "humanScore 必须在 0~1 之间");
        }
        CaseCalibrationLabel existing = calibrationMapper.selectOne(
                new LambdaQueryWrapper<CaseCalibrationLabel>()
                        .eq(CaseCalibrationLabel::getRunItemId, itemId)
                        .eq(CaseCalibrationLabel::getReviewerId, principal.userId())
                        .last("LIMIT 1"));
        if (existing == null) {
            existing = new CaseCalibrationLabel();
            existing.setTenantId(run.getTenantId());
            existing.setRunItemId(itemId);
            existing.setReviewerId(principal.userId());
            existing.setCreatedAt(LocalDateTime.now());
        }
        existing.setHumanScore(score.setScale(3, RoundingMode.HALF_UP));
        existing.setHumanLabel(label.toUpperCase());
        existing.setNote(truncate(str(req.get("note")), 512));
        existing.setUpdatedAt(LocalDateTime.now());
        if (existing.getId() == null) calibrationMapper.insert(existing);
        else calibrationMapper.updateById(existing);
        recomputeConsensus(item, run.getTenantId());
        auditService.record(principal.userId(), "case.calibration", "test_case_run_item",
                String.valueOf(itemId), "SUCCESS",
                Map.of("humanLabel", label.toUpperCase(), "humanScore", score));
        return calibrationView(item, run);
    }

    private void recomputeConsensus(TestCaseRunItem item, Long tenantId) {
        List<CaseCalibrationLabel> labels = calibrationMapper.selectList(
                new LambdaQueryWrapper<CaseCalibrationLabel>()
                        .eq(CaseCalibrationLabel::getRunItemId, item.getId())
                        .eq(CaseCalibrationLabel::getTenantId, tenantId)
                        .orderByAsc(CaseCalibrationLabel::getId));
        item.setLabelCount(labels.size());
        if (labels.isEmpty()) {
            item.setHumanScore(null);
            item.setHumanLabel(null);
            item.setReviewerId(null);
            item.setReviewedAt(null);
            item.setReviewNote(null);
        } else {
            double sum = 0;
            int scored = 0;
            long pass = labels.stream().filter(l -> "PASS".equalsIgnoreCase(l.getHumanLabel())).count();
            long fail = labels.size() - pass;
            BigDecimal avg = BigDecimal.ZERO;
            for (CaseCalibrationLabel l : labels) {
                if (l.getHumanScore() != null) {
                    sum += l.getHumanScore().doubleValue();
                    scored++;
                }
            }
            if (scored > 0) avg = BigDecimal.valueOf(sum / scored).setScale(3, RoundingMode.HALF_UP);
            String consensus = pass > fail ? "PASS" : fail > pass ? "FAIL"
                    : avg.doubleValue() >= 0.6 ? "PASS" : "FAIL";
            item.setHumanScore(avg);
            item.setHumanLabel(consensus);
            item.setReviewerId(labels.get(labels.size() - 1).getReviewerId());
            item.setReviewedAt(LocalDateTime.now());
            item.setReviewNote("标注 " + labels.size() + " 人；PASS=" + pass + " FAIL=" + fail);
        }
        runItemMapper.updateById(item);
    }

    private Map<String, Object> calibrationView(TestCaseRunItem i, TestCaseRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("runId", i.getRunId());
        m.put("runName", run == null ? null : run.getName());
        m.put("modelName", run == null ? null : run.getModelName());
        m.put("judgeModel", run == null ? null : run.getJudgeModel());
        m.put("caseId", i.getCaseId());
        m.put("question", i.getQuestion());
        m.put("answer", i.getAnswer());
        m.put("judgeScore", i.getScore());
        m.put("judgePassed", i.getPassed() != null && i.getPassed() == 1);
        TestCase c = i.getCaseId() == null ? null : caseMapper.selectById(i.getCaseId());
        m.put("referenceAnswer", c == null ? null : reference(c));
        m.put("humanScore", i.getHumanScore());
        m.put("humanLabel", i.getHumanLabel());
        m.put("labelCount", i.getLabelCount());
        m.put("labels", calibrationMapper.selectList(new LambdaQueryWrapper<CaseCalibrationLabel>()
                .eq(CaseCalibrationLabel::getRunItemId, i.getId())
                .orderByAsc(CaseCalibrationLabel::getId))
                .stream().map(l -> Map.of("reviewerId", l.getReviewerId(),
                        "humanScore", l.getHumanScore() == null ? "" : l.getHumanScore(),
                        "humanLabel", l.getHumanLabel(),
                        "note", l.getNote() == null ? "" : l.getNote())).toList());
        m.put("reviewerId", i.getReviewerId());
        m.put("reviewedAt", i.getReviewedAt());
        m.put("reviewNote", i.getReviewNote());
        m.put("error", i.getError());
        return m;
    }

    private LocalDateTime dateTime(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return LocalDateTime.parse(String.valueOf(raw).replace("Z", ""));
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(String.valueOf(raw)).toLocalDateTime();
            } catch (Exception ex) {
                throw new BusinessException(400, "expiresAt 不是合法日期时间");
            }
        }
    }

    private BigDecimal decimal(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (Exception e) {
            throw new BusinessException(400, "humanScore 不是合法数字");
        }
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public Map<String, Object> runs(JwtPrincipal principal) {
        List<TestCaseRun> rows = runMapper.selectList(new LambdaQueryWrapper<TestCaseRun>()
                .eq(TestCaseRun::getTenantId, principal.tenantId())
                .orderByDesc(TestCaseRun::getId).last("LIMIT 50"));
        return Map.of("list", rows.stream().map(this::runView).toList(), "total", rows.size());
    }

    public Map<String, Object> runDetail(Long id, JwtPrincipal principal) {
        TestCaseRun run = runMapper.selectById(id);
        if (run == null || !run.getTenantId().equals(principal.tenantId())) {
            throw new BusinessException(404, "回归记录不存在");
        }
        List<Map<String, Object>> items = runItemMapper.selectList(new LambdaQueryWrapper<TestCaseRunItem>()
                        .eq(TestCaseRunItem::getRunId, id).orderByAsc(TestCaseRunItem::getId))
                .stream().map(this::runItemView).toList();
        Map<String, Object> result = new LinkedHashMap<>(runView(run));
        result.put("items", items);
        return result;
    }

    // ---------- helpers ----------

    private List<TestCase> selectForRun(Object caseIdsRaw, JwtPrincipal principal) {
        return selectForRun(caseIdsRaw, principal, null);
    }

    private List<TestCase> selectForRun(Object caseIdsRaw, JwtPrincipal principal, String tier) {
        List<Long> ids = new ArrayList<>();
        if (caseIdsRaw instanceof List<?> list) {
            for (Object o : list) {
                Long id = toLong(o);
                if (id != null) ids.add(id);
            }
        }
        return caseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getTenantId, principal.tenantId())
                .eq(TestCase::getStatus, "APPROVED")
                .eq(tier != null && !tier.isBlank(), TestCase::getDatasetTier, tier)
                .in(!ids.isEmpty(), TestCase::getId, ids)
                .orderByDesc(TestCase::getId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedConfig(Long agentId) {
        if (agentId == null) return null;
        AgentVersion v = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agentId)
                        .orderByDesc(AgentVersion::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (v == null || v.getSnapshot() == null) return null;
        try {
            return objectMapper.readValue(v.getSnapshot(), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private double judge(String question, String reference, String answer, String judgeModel) {
        if (answer == null || answer.isBlank()) return 0;
        boolean noReference = reference == null || reference.isBlank();
        String prompt = noReference
                ? """
                用户问题：%s
                待评回答：%s

                请直接评估回答是否正确、切题、完整、无编造，不要求与某个参考答案逐字一致。
                严格输出 JSON：{"score":0到1,"reason":"20字内"}
                """.formatted(question, answer)
                : """
                用户问题：%s
                参考答案：%s
                待评回答：%s

                请评估待评回答是否切题、准确、与参考答案语义一致（允许表达不同）。
                严格输出 JSON：{"score":0到1,"reason":"20字内"}
                """.formatted(question, reference, answer);
        try {
            var resp = ChatClient.create(chatModelRouter.resolve(judgeModel)).prompt()
                    .options(chatModelRouter.options(judgeModel, 0.1, List.of()))
                    .system("你是模型回归裁判，只输出 JSON。")
                    .user(prompt).call().chatResponse();
            String text = resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
            JsonNode node = objectMapper.readTree(stripFence(text));
            return Math.max(0, Math.min(1, node.path("score").asDouble(0.5)));
        } catch (Exception e) {
            log.warn("回归裁判失败：{}", e.getMessage());
            return 0.5;
        }
    }

    /** 经典用例用原回答作参考；坏例原回答就是失败样本，不能用它当参考。 */
    private String reference(TestCase c) {
        if (c.getExpectedAnswer() != null && !c.getExpectedAnswer().isBlank()) {
            return c.getExpectedAnswer();
        }
        return "CLASSIC".equals(c.getKind()) ? c.getActualAnswer() : null;
    }

    private TestCase owned(Long id, JwtPrincipal principal) {
        TestCase c = caseMapper.selectById(id);
        if (c == null || !c.getTenantId().equals(principal.tenantId())) {
            throw new BusinessException(404, "用例不存在");
        }
        return c;
    }

    private Map<String, Object> view(TestCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("agentId", c.getAgentId());
        m.put("conversationId", c.getConversationId());
        m.put("assistantMessageId", c.getAssistantMessageId());
        m.put("question", c.getQuestion());
        m.put("actualAnswer", c.getActualAnswer());
        m.put("expectedAnswer", c.getExpectedAnswer());
        m.put("kind", c.getKind());
        m.put("source", c.getSource());
        m.put("status", c.getStatus());
        m.put("datasetTier", c.getDatasetTier());
        m.put("riskLevel", c.getRiskLevel());
        m.put("coverageTags", parseJson(c.getCoverageTags()));
        m.put("expiresAt", c.getExpiresAt());
        m.put("tags", parseJson(c.getTags()));
        m.put("note", c.getNote());
        m.put("traceId", c.getTraceId());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private Map<String, Object> runView(TestCaseRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("modelName", r.getModelName());
        m.put("judgeModel", r.getJudgeModel());
        m.put("total", r.getTotal());
        m.put("passed", r.getPassed());
        m.put("avgScore", r.getAvgScore());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<String, Object> runItemView(TestCaseRunItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("caseId", i.getCaseId());
        m.put("question", i.getQuestion());
        m.put("answer", i.getAnswer());
        m.put("score", i.getScore());
        m.put("humanScore", i.getHumanScore());
        m.put("humanLabel", i.getHumanLabel());
        m.put("labelCount", i.getLabelCount());
        m.put("reviewedAt", i.getReviewedAt());
        m.put("reviewNote", i.getReviewNote());
        m.put("passed", i.getPassed() != null && i.getPassed() == 1);
        m.put("latencyMs", i.getLatencyMs());
        m.put("error", i.getError());
        return m;
    }

    private String tagsJson(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String text) return text.isBlank() ? null : text;
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private String stripFence(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) return trimmed.substring(first + 1, last).trim();
        }
        return trimmed;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long toLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
