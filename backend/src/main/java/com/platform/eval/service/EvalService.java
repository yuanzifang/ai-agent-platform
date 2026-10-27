package com.platform.eval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.agent.service.ChatService;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.service.PricingService;
import com.platform.billing.service.QuotaService;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.eval.entity.EvalDataset;
import com.platform.eval.entity.EvalDatasetItem;
import com.platform.eval.entity.EvalResult;
import com.platform.eval.entity.EvalTask;
import com.platform.eval.mapper.EvalDatasetItemMapper;
import com.platform.eval.mapper.EvalDatasetMapper;
import com.platform.eval.mapper.EvalResultMapper;
import com.platform.eval.mapper.EvalTaskMapper;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentTraceMapper;
import com.platform.observability.service.TraceService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测执行（需求 3.5 / 评测流程）：创建任务 → 遍历样本 → 调 Agent（含 RAG）→
 * 产生 agent_trace → LLM 裁判打分 → eval_result → 更新状态与汇总指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalService {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*?\\}", Pattern.DOTALL);

    private final EvalDatasetMapper datasetMapper;
    private final EvalDatasetItemMapper itemMapper;
    private final EvalTaskMapper taskMapper;
    private final EvalResultMapper resultMapper;
    private final AgentRegistryMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final ChatService chatService;
    private final TraceService traceService;
    private final AgentTraceMapper traceMapper;
    private final com.platform.system.mapper.SysConfigMapper sysConfigMapper;
    private final com.platform.conversation.mapper.MessageMapper messageMapper;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;
    private final QuotaService quotaService;
    private final com.platform.knowledge.service.EmbeddingService embeddingService;
    private final com.platform.knowledge.service.VectorRepository vectorRepository;
    @org.springframework.beans.factory.annotation.Qualifier("llmTaskExecutor")
    private final org.springframework.core.task.TaskExecutor llmExecutor;
    private final ObjectMapper objectMapper;
    private final StepEvaluationService stepEvaluationService;
    /** 自身代理：用于以独立事务（REQUIRES_NEW）执行门禁评测（自调用不经代理会使事务注解失效）。 */
    private final org.springframework.beans.factory.ObjectProvider<EvalService> selfProvider;

    // ---------- 数据集 ----------

    public List<Map<String, Object>> datasets() {
        return datasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                        .orderByDesc(EvalDataset::getId))
                .stream().map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("name", d.getName());
                    m.put("description", d.getDescription());
                    m.put("agentId", d.getAgentId());
                    m.put("itemCount", itemMapper.selectCount(new LambdaQueryWrapper<EvalDatasetItem>()
                            .eq(EvalDatasetItem::getDatasetId, d.getId())));
                    m.put("updatedAt", d.getUpdatedAt());
                    return m;
                }).toList();
    }

    public Map<String, Object> createDataset(String name, String description, Long agentId,
                                             JwtPrincipal principal) {
        EvalDataset d = new EvalDataset();
        d.setName(name);
        d.setDescription(description);
        d.setAgentId(agentId);
        d.setTenantId(principal.tenantId());
        d.setCreatedBy(principal.userId());
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        datasetMapper.insert(d);
        return Map.of("id", d.getId());
    }

    public void deleteDataset(Long id) {
        Long used = taskMapper.selectCount(new LambdaQueryWrapper<EvalTask>()
                .eq(EvalTask::getDatasetId, id));
        if (used != null && used > 0) {
            throw new BusinessException(400, "数据集已被评测任务引用，禁止删除（需求 3.5(6)）");
        }
        itemMapper.delete(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, id));
        datasetMapper.deleteById(id);
    }

    public List<EvalDatasetItem> items(Long datasetId) {
        return itemMapper.selectList(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, datasetId)
                .orderByAsc(EvalDatasetItem::getId));
    }

    public void addItem(Long datasetId, String question, String expectedAnswer, String scoreCriteria,
                        JwtPrincipal principal) {
        addItem(datasetId, question, expectedAnswer, scoreCriteria, "ANSWER", null, null, principal);
    }

    public void addItem(Long datasetId, String question, String expectedAnswer, String scoreCriteria,
                        String stepType, String expectedPayload, String stepConfig, JwtPrincipal principal) {
        if (question == null || question.isBlank()) throw new BusinessException(400, "样本问题必填");
        EvalDatasetItem item = new EvalDatasetItem();
        item.setDatasetId(datasetId);
        item.setQuestion(question);
        item.setExpectedAnswer(expectedAnswer);
        item.setScoreCriteria(scoreCriteria);
        String normalizedStep = normalizeStepType(stepType);
        String payload = blankToNull(expectedPayload);
        String config = blankToNull(stepConfig);
        String stepError = EvalStepCatalog.validate(normalizedStep, payload, config);
        if (stepError != null) throw new BusinessException(400, stepError);
        item.setStepType(normalizedStep);
        item.setExpectedPayload(payload);
        item.setStepConfig(config);
        item.setTenantId(principal.tenantId());
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);
        touchDataset(datasetId);
    }

    /** 编辑样本：保留 id 与历史引用，修改后刷新数据集时间以失效门禁缓存。 */
    public void updateItem(Long itemId, String question, String expectedAnswer, String scoreCriteria,
                           String stepType, String expectedPayload, String stepConfig) {
        if (question == null || question.isBlank()) throw new BusinessException(400, "样本问题必填");
        EvalDatasetItem item = itemMapper.selectById(itemId);
        if (item == null) throw new BusinessException(404, "样本不存在");
        String normalizedStep = normalizeStepType(stepType);
        String payload = blankToNull(expectedPayload);
        String config = blankToNull(stepConfig);
        String stepError = EvalStepCatalog.validate(normalizedStep, payload, config);
        if (stepError != null) throw new BusinessException(400, stepError);
        item.setQuestion(question);
        item.setExpectedAnswer(expectedAnswer);
        item.setScoreCriteria(scoreCriteria);
        item.setStepType(normalizedStep);
        item.setExpectedPayload(payload);
        item.setStepConfig(config);
        itemMapper.updateById(item);
        touchDataset(item.getDatasetId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeStepType(String stepType) {
        String value = stepType == null ? "ANSWER" : stepType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ANSWER", "REWRITE", "RETRIEVAL", "RERANK").contains(value)) {
            throw new BusinessException(400, "不支持的步骤类型：" + stepType);
        }
        return value;
    }

    /** 数据集内容变更时刷新 updated_at，使预发布门禁缓存失效（preReleaseGate）。 */
    private void touchDataset(Long datasetId) {
        EvalDataset patch = new EvalDataset();
        patch.setId(datasetId);
        patch.setUpdatedAt(LocalDateTime.now());
        datasetMapper.updateById(patch);
    }

    /** JSONL 批量导入：每行 {"question":..,"expectedAnswer":..,"scoreCriteria":..}。 */
    public int importItems(Long datasetId, String jsonl, JwtPrincipal principal) {
        int count = 0;
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> row = objectMapper.readValue(line.trim(), Map.class);
                addItem(datasetId, str(row.get("question")), str(row.get("expectedAnswer")),
                        str(row.get("scoreCriteria")), str(row.get("stepType")),
                        jsonString(row.get("expectedPayload")), jsonString(row.get("stepConfig")), principal);
                count++;
            } catch (Exception e) {
                throw new BusinessException(400, "样本格式错误（第 " + (count + 1) + " 行）：" + e.getMessage());
            }
        }
        return count;
    }

    public void deleteItem(Long itemId) {
        EvalDatasetItem item = itemMapper.selectById(itemId);
        itemMapper.deleteById(itemId);
        if (item != null) touchDataset(item.getDatasetId());
    }

    /** 兼容旧调用：无 Agent 上下文。 */
    public Map<String, Object> runStep(String stepType, String query, String expectedPayload,
                                       String stepConfig, JwtPrincipal principal) {
        return runStep(stepType, query, expectedPayload, stepConfig, null, principal);
    }

    /** 单步即时评测：直接返回步骤结果，不落评测任务。 */
    public Map<String, Object> runStep(String stepType, String query, String expectedPayload,
                                       String stepConfig, Long agentId, JwtPrincipal principal) {
        String type = stepType == null ? "ANSWER" : stepType.toUpperCase(java.util.Locale.ROOT);
        KnowledgePermissionService.Viewer viewer =
                new KnowledgePermissionService.Viewer(principal.userId(), principal.roles());
        StepEvaluationService.StepResult result = "ANSWER".equals(type)
                ? answerStep(agentId, query, expectedPayload, stepConfig, principal)
                : stepEvaluationService.evaluate(type, query, expectedPayload, stepConfig, viewer);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stepType", type);
        response.put("score", result.score());
        response.put("passed", result.passed());
        response.put("actual", result.actual());
        try {
            response.put("details", objectMapper.readValue(result.details(), Map.class));
        } catch (Exception e) {
            response.put("details", result.details());
        }
        return response;
    }

    /** ANSWER 单步：使用 Agent 已发布配置执行回答，再用平台裁判评分；不创建任务。 */
    private StepEvaluationService.StepResult answerStep(Long agentId, String query,
                                                        String expectedPayload, String stepConfig,
                                                        JwtPrincipal principal) {
        if (agentId == null) throw new BusinessException(400, "ANSWER 单步评测需要 agentId");
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) {
            throw new BusinessException(404, "Agent 不存在");
        }
        Map<String, Object> config;
        try {
            config = publishedConfig(agentId);
        } catch (BusinessException e) {
            config = editStateConfig(agentId);
        }
        Map<String, Object> expected = parseMap(expectedPayload);
        Map<String, Object> stepCfg = parseMap(stepConfig);
        String question = query == null ? "" : query;
        String systemPrompt = str(config.get("systemPrompt"));
        systemPrompt = applyVarDefaults(systemPrompt, config.get("variables"));
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> kbBindings = knowledgeBindings(agentId);
        if (!kbBindings.isEmpty()) {
            systemPrompt = chatService.augmentWithKnowledge(systemPrompt, question, kbBindings, citations,
                    KnowledgePermissionService.Viewer.of(agent.getOwnerId(), null));
        }
        Map<String, Object> modelConfig = config.get("modelConfig") instanceof Map<?, ?> mc
                ? castStringMap(mc) : Map.of();
        String modelName = str(modelConfig.getOrDefault("modelName", "deepseek-flash"));
        long startedAt = System.currentTimeMillis();
        var spec = ChatClient.create(chatModelRouter.resolve(modelName)).prompt()
                .options(chatModelRouter.options(modelName, null, List.of()));
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);
        var agentResp = spec.user(question).call().chatResponse();
        String answer = agentResp.getResult() == null ? "" : agentResp.getResult().getOutput().getText();
        long agentLatency = System.currentTimeMillis() - startedAt;

        String expectedAnswer = str(expected.getOrDefault("expectedAnswer",
                expected.getOrDefault("answer", "")));
        String scoreCriteria = str(stepCfg.getOrDefault("scoreCriteria",
                expected.getOrDefault("scoreCriteria", "")));
        String judgeModel = str(stepCfg.getOrDefault("judgeModel", "deepseek-flash"));
        String judgePrompt = """
                你是严格的评测裁判。根据评分标准对 AI 回答打分。
                【问题】%s
                【期望答案】%s
                【评分标准】%s
                【AI 回答】%s
                只输出严格 JSON：{"score": 0到1的小数, "reason": "30字内理由"}
                """.formatted(question, expectedAnswer == null || expectedAnswer.isBlank() ? "（无）" : expectedAnswer,
                scoreCriteria == null || scoreCriteria.isBlank() ? "回答须与期望答案事实一致、完整、不编造" : scoreCriteria,
                answer == null ? "" : answer);
        var judgeResp = ChatClient.create(chatModelRouter.resolve(judgeModel)).prompt()
                .options(OpenAiChatOptions.builder().model(judgeModel).build())
                .system("你是评测裁判，只输出 JSON，不要任何其他文字。")
                .user(judgePrompt).call().chatResponse();
        String judgeText = judgeResp.getResult() == null ? "" : judgeResp.getResult().getOutput().getText();
        double score = 0;
        String reason = "解析失败";
        Matcher matcher = JSON_BLOCK.matcher(judgeText == null ? "" : judgeText);
        if (matcher.find()) {
            try {
                Map<?, ?> parsed = objectMapper.readValue(matcher.group(), Map.class);
                score = Double.parseDouble(String.valueOf(parsed.get("score") == null ? "0" : parsed.get("score")));
                reason = str(parsed.get("reason"));
            } catch (Exception ignored) {
            }
        }
        score = Math.max(0, Math.min(1, score));
        double threshold = stepCfg.get("passThreshold") instanceof Number n ? n.doubleValue() : 0.6;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("model", modelName);
        details.put("judgeModel", judgeModel);
        details.put("reason", reason);
        details.put("expectedAnswer", expectedAnswer);
        details.put("scoreCriteria", scoreCriteria);
        details.put("latencyMs", agentLatency);
        details.put("citations", citations.size());
        return new StepEvaluationService.StepResult(score, score >= threshold, answer, jsonString(details));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castStringMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException(400, "expectedPayload/stepConfig 必须是合法 JSON");
        }
    }

    /** 人工复核步骤/回答评测结果；仅记录人工确认，不覆盖确定性分数。 */
    public Map<String, Object> reviewResult(Long resultId, boolean reviewed, String note, JwtPrincipal principal) {
        EvalResult result = resultMapper.selectById(resultId);
        if (result == null) throw new BusinessException(404, "评测结果不存在");
        String status = reviewed ? "REVIEWED" : "PENDING";
        LocalDateTime reviewedAt = reviewed ? LocalDateTime.now() : null;
        Long reviewedBy = reviewed ? principal.userId() : null;
        String reviewNote = note == null || note.isBlank() ? null
                : note.substring(0, Math.min(note.length(), 1000));
        resultMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EvalResult>()
                .eq(EvalResult::getId, resultId)
                .set(EvalResult::getReviewStatus, status)
                .set(EvalResult::getReviewNote, reviewNote)
                .set(EvalResult::getReviewedBy, reviewedBy)
                .set(EvalResult::getReviewedAt, reviewedAt));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", resultId);
        response.put("reviewStatus", status);
        response.put("reviewNote", reviewNote);
        response.put("reviewedBy", reviewedBy);
        response.put("reviewedAt", reviewedAt);
        return response;
    }

    /** 数据集历史趋势：最近 N 个已完成任务的分步骤指标。 */
    public Map<String, Object> datasetTrend(Long datasetId, Long agentId, int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        List<EvalTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<EvalTask>()
                .eq(EvalTask::getDatasetId, datasetId)
                .eq(agentId != null, EvalTask::getAgentId, agentId)
                .eq(EvalTask::getStatus, "DONE")
                .orderByDesc(EvalTask::getId)
                .last("LIMIT " + size));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EvalTask task : tasks) {
            List<EvalResult> results = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                    .eq(EvalResult::getTaskId, task.getId()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", task.getId());
            row.put("name", task.getName());
            row.put("agentId", task.getAgentId());
            row.put("evalType", task.getEvalType());
            row.put("createdAt", task.getCreatedAt());
            row.put("avgScore", task.getAvgScore());
            row.put("passRate", task.getPassRate());
            row.put("stepSummary", StepMetrics.summarize(results));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("tasks", rows);
        return result;
    }

    /** 两个任务的分步骤对比：用于确认检索/重排/改写改动是否带来回归。 */
    public Map<String, Object> compareTasks(Long taskId, Long baselineTaskId) {
        EvalTask currentTask = taskMapper.selectById(taskId);
        EvalTask baselineTask = taskMapper.selectById(baselineTaskId);
        if (currentTask == null || baselineTask == null) {
            throw new BusinessException(404, "评测任务不存在");
        }
        List<EvalResult> current = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, taskId));
        List<EvalResult> baseline = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, baselineTaskId));
        Map<String, Object> comparison = StepMetrics.compare(
                StepMetrics.summarize(current), StepMetrics.summarize(baseline));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("baselineTaskId", baselineTaskId);
        result.put("comparison", comparison);
        return result;
    }

    /** 跨数据集步骤覆盖矩阵，按 Agent 过滤。 */
    public Map<String, Object> coverage(Long agentId) {
        List<EvalDataset> datasets = datasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                .eq(agentId != null, EvalDataset::getAgentId, agentId)
                .orderByDesc(EvalDataset::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String type : List.of("ANSWER", "REWRITE", "RETRIEVAL", "RERANK")) totals.put(type, 0);
        for (EvalDataset dataset : datasets) {
            List<EvalDatasetItem> items = itemMapper.selectList(new LambdaQueryWrapper<EvalDatasetItem>()
                    .eq(EvalDatasetItem::getDatasetId, dataset.getId()));
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String type : totals.keySet()) counts.put(type, 0);
            for (EvalDatasetItem item : items) {
                String type = item.getStepType() == null ? "ANSWER"
                        : item.getStepType().toUpperCase(java.util.Locale.ROOT);
                counts.merge(type, 1, Integer::sum);
                totals.merge(type, 1, Integer::sum);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("datasetId", dataset.getId());
            row.put("name", dataset.getName());
            row.put("agentId", dataset.getAgentId());
            row.put("total", items.size());
            row.put("steps", counts);
            row.put("missing", counts.entrySet().stream().filter(e -> e.getValue() == 0)
                    .map(Map.Entry::getKey).toList());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasets", rows);
        result.put("totalByType", totals);
        result.put("missingTypes", totals.entrySet().stream().filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey).toList());
        return result;
    }

    /** 按步骤类型统计数据集覆盖，便于补齐改写/检索/重排/回答黄金集。 */
    public Map<String, Object> stepSummary(Long datasetId) {
        List<EvalDatasetItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<EvalDatasetItem>().eq(EvalDatasetItem::getDatasetId, datasetId));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : List.of("ANSWER", "REWRITE", "RETRIEVAL", "RERANK")) counts.put(type, 0);
        for (EvalDatasetItem item : items) {
            String type = item.getStepType() == null ? "ANSWER"
                    : item.getStepType().toUpperCase(java.util.Locale.ROOT);
            counts.merge(type, 1, Integer::sum);
        }
        List<String> missing = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == 0).map(Map.Entry::getKey).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("total", items.size());
        result.put("steps", counts);
        result.put("missing", missing);
        return result;
    }

    // ---------- 评测任务 ----------

    public Map<String, Object> tasks(Long agentId) {
        return Map.of("list", taskMapper.selectList(new LambdaQueryWrapper<EvalTask>()
                        .eq(agentId != null, EvalTask::getAgentId, agentId)
                        .orderByDesc(EvalTask::getId))
                .stream().map(this::taskView).toList());
    }

    public Map<String, Object> taskDetail(Long taskId) {
        EvalTask t = taskMapper.selectById(taskId);
        if (t == null) throw new BusinessException(404, "任务不存在");
        List<EvalResult> results = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, taskId).orderByAsc(EvalResult::getId));
        Map<String, Object> detail = taskView(t);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EvalResult r : results) {
            EvalDatasetItem item = itemMapper.selectById(r.getDatasetItemId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", item == null ? "" : item.getQuestion());
            row.put("expectedAnswer", item == null ? "" : item.getExpectedAnswer());
            row.put("answer", r.getAnswer());
            row.put("score", r.getScore());
            row.put("passed", r.getPassed() != null && r.getPassed() == 1);
            row.put("judgeReason", r.getJudgeReason());
            row.put("stepType", r.getStepType());
            row.put("stepPassed", r.getStepPassed() != null && r.getStepPassed() == 1);
            row.put("stepDetails", r.getStepDetails());
            row.put("reviewStatus", r.getReviewStatus());
            row.put("reviewNote", r.getReviewNote());
            row.put("reviewedBy", r.getReviewedBy());
            row.put("reviewedAt", r.getReviewedAt());
            row.put("traceId", r.getTraceId());
            row.put("latencyMs", r.getLatencyMs());
            row.put("tokens", r.getTokens());
            rows.add(row);
        }
        detail.put("results", rows);
        Map<String, Object> stepSummary = StepMetrics.summarize(results);
        detail.put("stepSummary", stepSummary);
        if ("PRE_RELEASE".equals(t.getEvalType())) {
            detail.put("stepGate", stepGate(t.getId(), gateThreshold()));
        }
        return detail;
    }

    private Map<String, Object> taskView(EvalTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("agentId", t.getAgentId());
        m.put("agentVersion", t.getAgentVersion());
        m.put("evalType", t.getEvalType());
        m.put("judgeModel", t.getJudgeModel());
        m.put("datasetId", t.getDatasetId());
        EvalDataset ds = t.getDatasetId() == null ? null : datasetMapper.selectById(t.getDatasetId());
        m.put("datasetName", ds == null ? "-" : ds.getName());
        m.put("status", t.getStatus());
        m.put("estimatedCalls", t.getEstimatedCalls());
        m.put("completedCalls", t.getCompletedCalls());
        m.put("avgScore", t.getAvgScore());
        m.put("passRate", t.getPassRate());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }

    /** 创建任务并异步执行（评测流程入口）。 */
    public Map<String, Object> createTask(String name, Long agentId, Long datasetId, String judgeModel,
                                          Double passThreshold, JwtPrincipal principal) {
        return createTask(name, agentId, datasetId, judgeModel, passThreshold, Map.of(), principal);
    }

    public Map<String, Object> createTask(String name, Long agentId, Long datasetId, String judgeModel,
                                          Double passThreshold, Map<String, Object> extra, JwtPrincipal principal) {
        quotaService.checkTokensToday(1000);
        EvalTask t = createTaskRow(name, agentId, datasetId, judgeModel, passThreshold,
                extra.get("evalType") instanceof String et && !et.isBlank() ? et : "ONCE",
                extra.get("grayTag") instanceof String gt && !gt.isBlank() ? gt : null, principal);
        final double threshold = passThreshold;
        final Long tenantId = t.getTenantId();
        // 注意不能用 @Async 自调用（不走代理），直接提交线程池；租户上下文由 execute 内部还原
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                execute(t.getId(), tenantId, threshold);
            } catch (Exception e) {
                log.error("评测任务失败 taskId={}：{}", t.getId(), e.getMessage(), e);
                TenantContext.run(tenantId, () -> {
                    EvalTask fail = new EvalTask();
                    fail.setId(t.getId());
                    fail.setStatus("FAILED");
                    taskMapper.updateById(fail);
                });
            }
        }, llmExecutor);
        return Map.of("taskId", t.getId());
    }

    /** 仅创建任务行（校验 + INSERT），执行策略由调用方决定。 */

    /** 评测轨迹 tags：eval + 类型 + 灰度标记（gray:<tag>，供 GrayCompareService 按任务分组对比）。 */
    static List<String> evalTraceTags(EvalTask task, String... extraTags) {
        List<String> tags = new ArrayList<>(List.of("eval"));
        for (String extraTag : extraTags) {
            if (extraTag != null && !extraTag.isBlank()) tags.add(extraTag);
        }
        if (task.getGrayTag() != null && !task.getGrayTag().isBlank()) {
            tags.add("gray:" + task.getGrayTag().trim());
        }
        return tags;
    }
    private EvalTask createTaskRow(String name, Long agentId, Long datasetId,
                                   String judgeModel, Double passThreshold, String evalType,
                                   String grayTag, JwtPrincipal principal) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BusinessException(404, "Agent 不存在");
        AgentVersion latest = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agentId)
                        .orderByDesc(AgentVersion::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        Long samples = itemMapper.selectCount(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, datasetId));
        if (samples == null || samples == 0) throw new BusinessException(400, "数据集为空，无法评测");

        EvalTask t = new EvalTask();
        t.setName(name == null || name.isBlank() ? "评测-" + agent.getName() : name);
        t.setAgentId(agentId);
        t.setAgentVersion(latest == null ? null : latest.getVersion());
        t.setTargetType("AGENT");
        t.setEvalType(evalType);
        t.setGrayTag(grayTag == null || grayTag.isBlank() ? null : grayTag.trim());
        t.setJudgeModel(judgeModel == null || judgeModel.isBlank() ? "deepseek-flash" : judgeModel);
        t.setMetrics(toJson(List.of(Map.of("metricKey", "correctness", "name", "答案正确性",
                "passThreshold", passThreshold == null ? 0.6 : passThreshold))));
        t.setRounds(1);
        t.setStatus("PENDING");
        t.setEstimatedCalls(samples.intValue());
        t.setCompletedCalls(0);
        t.setDatasetId(datasetId);
        t.setTenantId(principal.tenantId());
        t.setCreatedBy(principal.userId());
        t.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(t);
        return t;
    }

    /**
     * 在线监控（需求 3.5）：按 trace 抽样线上成功对话，用裁判模型对「AI 实际回答」
     * 就「用户问题」做质量打分（无需期望答案，评忠实/相关/简洁），完成后回填 agent_trace.score。
     * 失败重试由调用方按指数退避处理（本实现最多 3 次）。
     */
    public Map<String, Object> runOnlineMonitoring(Long agentId, int sampleSize, JwtPrincipal principal) {
        List<AgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AgentTrace>()
                .eq(AgentTrace::getAgentId, agentId)
                .eq(AgentTrace::getStatus, "SUCCESS")
                .isNotNull(AgentTrace::getConversationId)   // 排除评测/集成 Trace（无会话消息可评分）
                .isNull(AgentTrace::getScore)
                .orderByDesc(AgentTrace::getId)
                .last("LIMIT " + Math.max(1, Math.min(sampleSize, 50))));
        return createOnlineTask(agentId, traces, principal.tenantId(), principal.userId());
    }

    /**
     * 定时抽样（需求 3.5 / 架构 7.5）：扫描最近 1 小时 SUCCESS 且未评分的 Trace，
     * 按 eval.sample-rate 比例抽样（每 Agent 至少 1 条、至多 50 条），逐 Agent 创建 ONLINE 任务。
     * 调用方须已设置租户上下文；返回创建的任务数。
     */
    public int runOnlineSampling(Long tenantId, double rate) {
        List<AgentTrace> recent = traceMapper.selectList(new LambdaQueryWrapper<AgentTrace>()
                .eq(AgentTrace::getStatus, "SUCCESS")
                .isNotNull(AgentTrace::getConversationId)   // 排除评测/集成 Trace（无会话消息可评分）
                .isNull(AgentTrace::getScore)
                .ge(AgentTrace::getCreatedAt, LocalDateTime.now().minusHours(1))
                .orderByDesc(AgentTrace::getId));
        if (recent.isEmpty()) return 0;
        Map<Long, List<AgentTrace>> byAgent = recent.stream()
                .filter(t -> t.getAgentId() != null)
                .collect(java.util.stream.Collectors.groupingBy(AgentTrace::getAgentId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        int created = 0;
        for (Map.Entry<Long, List<AgentTrace>> e : byAgent.entrySet()) {
            int n = Math.max(1, (int) Math.round(e.getValue().size() * rate));
            List<AgentTrace> picked = e.getValue().subList(0, Math.min(n, e.getValue().size()));
            Map<String, Object> r = createOnlineTask(e.getKey(), picked, tenantId, 0L);
            if (((Number) r.getOrDefault("sampled", 0)).intValue() > 0) created++;
        }
        return created;
    }

    /** 抽样后建任务并异步执行（手动/定时共用）。 */
    private Map<String, Object> createOnlineTask(Long agentId, List<AgentTrace> traces,
                                                 Long tenantId, Long userId) {
        if (traces.isEmpty()) return Map.of("taskId", 0, "sampled", 0);
        quotaService.checkTokensToday(500);

        EvalTask t = new EvalTask();
        t.setName("在线监控-" + LocalDate.now());
        t.setAgentId(agentId);
        t.setTargetType("AGENT");
        t.setEvalType("ONLINE");
        t.setJudgeModel("deepseek-flash");
        t.setMetrics(toJson(List.of(Map.of("metricKey", "online_quality", "name", "线上回答质量"))));
        t.setRounds(1);
        t.setStatus("RUNNING");
        t.setEstimatedCalls(traces.size());
        t.setCompletedCalls(0);
        t.setTenantId(tenantId);
        t.setCreatedBy(userId);
        t.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(t);

        final Long taskId = t.getId();
        final List<Long> traceIds = traces.stream().map(AgentTrace::getId).toList();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                executeOnline(taskId, tenantId, agentId, traceIds);
            } catch (Exception e) {
                log.error("在线监控失败 taskId={}：{}", taskId, e.getMessage(), e);
                TenantContext.run(tenantId, () -> {
                    EvalTask fail = new EvalTask();
                    fail.setId(taskId);
                    fail.setStatus("FAILED");
                    taskMapper.updateById(fail);
                });
            }
        }, llmExecutor);
        return Map.of("taskId", taskId, "sampled", traces.size());
    }

    void executeOnline(Long taskId, Long tenantId, Long agentId, List<Long> traceIds) {
        TenantContext.run(tenantId, () -> {
            double scoreSum = 0;
            int done = 0, failed = 0;
            for (Long traceRowId : traceIds) {
                try {
                    AgentTrace trace = traceMapper.selectById(traceRowId);
                    if (trace == null) continue;
                    // 找该 trace 对应的 ASSISTANT 消息（含上一条 USER 问题）
                    com.platform.conversation.entity.Message ai = messageMapper.selectList(
                                    new LambdaQueryWrapper<com.platform.conversation.entity.Message>()
                                            .eq(com.platform.conversation.entity.Message::getTraceId, trace.getTraceId()))
                            .stream().findFirst().orElse(null);
                    if (ai == null) { done++; continue; }
                    com.platform.conversation.entity.Message question = messageMapper.selectList(
                                    new LambdaQueryWrapper<com.platform.conversation.entity.Message>()
                                            .eq(com.platform.conversation.entity.Message::getConversationId, ai.getConversationId())
                                            .eq(com.platform.conversation.entity.Message::getRole, "USER")
                                            .lt(com.platform.conversation.entity.Message::getId, ai.getId())
                                            .orderByDesc(com.platform.conversation.entity.Message::getId)
                                            .last("LIMIT 1"))
                            .stream().findFirst().orElse(null);
                    double score = judgeOnlineWithRetry(question == null ? "" : question.getContent(),
                            ai.getContent() == null ? "" : ai.getContent(), "deepseek-flash");

                    EvalResult r = new EvalResult();
                    r.setTaskId(taskId);
                    r.setDatasetItemId(0L);
                    r.setTraceId(trace.getTraceId());
                    r.setMetricKey("online_quality");
                    r.setAnswer(ai.getContent());
                    r.setScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
                    r.setPassed(score >= 0.6 ? 1 : 0);
                    r.setLatencyMs(trace.getLatencyMs());
                    r.setTokens(trace.getInputTokens() == null ? 0 : trace.getInputTokens().intValue()
                            + (trace.getOutputTokens() == null ? 0 : trace.getOutputTokens().intValue()));
                    r.setTenantId(tenantId);
                    r.setCreatedAt(LocalDateTime.now());
                    resultMapper.insert(r);

                    // 回填 agent_trace.score（需求 3.5）：乐观条件 isNull，防止手动+定时并发重复评分覆盖；
                    // 评测失败不回填，保留原值（失败重试最多 3 次见 judgeOnlineWithRetry）
                    traceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentTrace>()
                            .eq(AgentTrace::getId, traceRowId)
                            .isNull(AgentTrace::getScore)
                            .set(AgentTrace::getScore, BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP)));

                    scoreSum += score;
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("在线监控单条失败 task={} trace={}：{}", taskId, traceRowId, e.getMessage());
                }
                EvalTask progress = new EvalTask();
                progress.setId(taskId);
                progress.setCompletedCalls(done + failed);
                taskMapper.updateById(progress);
            }
            EvalTask finish = new EvalTask();
            finish.setId(taskId);
            finish.setStatus("DONE");
            finish.setCompletedCalls(done + failed);
            finish.setAvgScore(done == 0 ? null
                    : BigDecimal.valueOf(scoreSum / done).setScale(4, RoundingMode.HALF_UP));
            // passRate = 评分覆盖率（在线监控无期望答案，通过率语义为已评分比例）
            finish.setPassRate(BigDecimal.valueOf((double) done / traceIds.size()).setScale(4, RoundingMode.HALF_UP));
            taskMapper.updateById(finish);
        });
    }

    /** 裁判调用失败重试：指数退避 1s/2s，最多 3 次（需求 3.5 在线监控补充）。 */
    private double judgeOnlineWithRetry(String question, String answer, String judgeModel) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return judgeOnline(question, answer, judgeModel)[0];
            } catch (RuntimeException e) {
                last = e;
                log.warn("在线裁判第 {} 次调用失败：{}", attempt + 1, e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(1000L << attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw last == null ? new IllegalStateException("裁判调用失败") : last;
    }

    /** 在线裁判：无期望答案，评回答质量（相关/诚实/简洁），返回 {score}。 */
    private double[] judgeOnline(String question, String answer, String judgeModel) {
        String prompt = """
                评估 AI 客服回答质量。只依据下方材料，不从外部补充事实。
                【用户问题】%s
                【AI回答】%s
                评分维度：是否切题、是否诚实（不编造）、是否简洁。输出严格 JSON：{"score": 0到1, "reason": "20字内"}
                """.formatted(question, answer);
        var resp = ChatClient.create(chatModelRouter.resolve(judgeModel)).prompt()
                .options(chatModelRouter.options(judgeModel, null, java.util.List.of()))
                .system("你是评测裁判，只输出 JSON。")
                .user(prompt).call().chatResponse();
        String text = resp.getResult().getOutput().getText();
        double score = 0;
        Matcher m = JSON_BLOCK.matcher(text == null ? "" : text);
        if (m.find()) {
            try {
                score = Double.parseDouble(String.valueOf(
                        objectMapper.readValue(m.group(), Map.class).getOrDefault("score", "0")));
            } catch (Exception e) {
                log.debug("评测解析忽略异常：{}", e.getMessage());
            }
        }
        return new double[]{Math.max(0, Math.min(1, score))};
    }

    /** 点踩触发 FEEDBACK 评测（需求 3.14(2)：P1 可选开关）。 */
    public void triggerFeedbackEval(Long conversationId, Long messageId, Long tenantId) {
        com.platform.conversation.entity.Message ai = messageMapper.selectById(messageId);
        if (ai == null) return;
        com.platform.conversation.entity.Message question = messageMapper.selectList(
                        new LambdaQueryWrapper<com.platform.conversation.entity.Message>()
                                .eq(com.platform.conversation.entity.Message::getConversationId, conversationId)
                                .eq(com.platform.conversation.entity.Message::getRole, "USER")
                                .lt(com.platform.conversation.entity.Message::getId, messageId)
                                .orderByDesc(com.platform.conversation.entity.Message::getId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (question == null) return;

        EvalTask t = new EvalTask();
        t.setName("点踩复核-msg" + messageId);
        t.setAgentId(0L);
        t.setEvalType("FEEDBACK");
        t.setJudgeModel("deepseek-flash");
        t.setMetrics(toJson(List.of(Map.of("metricKey", "feedback"))));
        t.setRounds(1);
        t.setStatus("RUNNING");
        t.setEstimatedCalls(1);
        t.setCompletedCalls(0);
        t.setTenantId(tenantId);
        t.setCreatedBy(0L);
        t.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(t);

        final Long taskId = t.getId();
        java.util.concurrent.CompletableFuture.runAsync(() ->
                TenantContext.run(tenantId, () -> {
                    try {
                        double score = judgeOnlineWithRetry(question.getContent(),
                                ai.getContent() == null ? "" : ai.getContent(), "deepseek-flash");
                        EvalResult r = new EvalResult();
                        r.setTaskId(taskId);
                        r.setDatasetItemId(0L);
                        r.setTraceId(ai.getTraceId());
                        r.setMetricKey("feedback");
                        r.setAnswer(ai.getContent());
                        r.setScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
                        r.setPassed(score >= 0.6 ? 1 : 0);
                        r.setTenantId(tenantId);
                        r.setCreatedAt(LocalDateTime.now());
                        resultMapper.insert(r);
                        EvalTask finish = new EvalTask();
                        finish.setId(taskId);
                        finish.setStatus("DONE");
                        finish.setCompletedCalls(1);
                        finish.setAvgScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
                        finish.setPassRate(BigDecimal.valueOf(score >= 0.6 ? 1 : 0));
                        taskMapper.updateById(finish);
                    } catch (Exception e) {
                        log.warn("点踩复核失败 task={} msg={}：{}", taskId, messageId, e.getMessage());
                        EvalTask fail = new EvalTask();
                        fail.setId(taskId);
                        fail.setStatus("FAILED");
                        fail.setCompletedCalls(1);
                        taskMapper.updateById(fail);
                    }
                }), llmExecutor);
    }

    /**
     * 预发布门禁（需求 3.5）：对指定数据集执行同步评测，返回通过率；
     * 通过率 < sys_config publish.gate-threshold（默认0.8）时由发布方阻断。
     */
    public java.util.Map<String, Object> preReleaseGate(Long agentId, Long datasetId, Long operatorId) {
        Long dsId = datasetId;
        if (dsId == null) {
            // 回落：该 Agent 绑定的默认数据集（eval_dataset.agent_id），无则跳过门禁
            EvalDataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                    .eq(EvalDataset::getAgentId, agentId).last("LIMIT 1"));
            if (ds == null) return Map.of("skipped", true, "reason", "未绑定默认数据集，跳过门禁");
            dsId = ds.getId();
        }
        Long samples = itemMapper.selectCount(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, dsId));
        if (samples == null || samples == 0) {
            return Map.of("skipped", true, "reason", "数据集为空，跳过门禁");
        }
        double threshold = gateThreshold();

        // 复用最近一次门禁结果：数据集与 Agent 编辑态自该任务创建后均未更新、样本数未变，
        // 即视为有效，避免每次发布重跑数分钟
        EvalDataset boundDs = datasetMapper.selectById(dsId);
        AgentRegistry gateAgent = agentMapper.selectById(agentId);
        EvalTask recent = taskMapper.selectList(new LambdaQueryWrapper<EvalTask>()
                        .eq(EvalTask::getAgentId, agentId)
                        .eq(EvalTask::getDatasetId, dsId)
                        .eq(EvalTask::getEvalType, "PRE_RELEASE")
                        .eq(EvalTask::getStatus, "DONE")
                        .orderByDesc(EvalTask::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        boolean changed = recent == null || boundDs == null
                || (boundDs.getUpdatedAt() != null && recent.getCreatedAt() != null
                    && boundDs.getUpdatedAt().isAfter(recent.getCreatedAt()))
                || (gateAgent != null && gateAgent.getUpdatedAt() != null && recent.getCreatedAt() != null
                    && gateAgent.getUpdatedAt().isAfter(recent.getCreatedAt()))
                || !Long.valueOf(samples).equals(Long.valueOf(recent.getEstimatedCalls() == null ? -1 : recent.getEstimatedCalls()));
        if (recent != null && !changed) {
            Map<String, Object> stepGate = stepGate(recent.getId(), threshold);
            boolean cachedPass = recent.getPassRate() != null
                    && recent.getPassRate().doubleValue() >= threshold
                    && ((List<?>) stepGate.get("failedSteps")).isEmpty();
            Map<String, Object> cached = new LinkedHashMap<>();
            cached.put("skipped", false);
            cached.put("passed", cachedPass);
            cached.put("taskId", recent.getId());
            cached.put("passRate", recent.getPassRate() == null ? 0 : recent.getPassRate().doubleValue());
            cached.put("threshold", threshold);
            cached.put("cached", true);
            cached.put("stepGate", stepGate);
            return cached;
        }
        // 同步执行（发布是同步操作），且放在独立事务（REQUIRES_NEW）中：
        // 1) 外层发布事务处于 RR 快照隔离，轮询 DB/读回任务状态都读不到异步提交——直接同步执行取内存结果；
        // 2) 阻断发布时回滚的是外层事务，评测记录与用量需独立提交保留（缓存与审计需要）。
        // 评测对象为编辑态配置（即将发布的版本）
        JwtPrincipal sysPrincipal = new com.platform.security.JwtPrincipal(
                operatorId, TenantContext.getTenantId(), false, List.of("SYSTEM"), List.of());
        TaskOutcome outcome = selfProvider.getObject().createAndExecuteExclusive(
                "预发布门禁-" + LocalDate.now(), agentId, dsId, gateJudgeModel,
                threshold, "PRE_RELEASE", sysPrincipal);
        Map<String, Object> stepGate = stepGate(outcome.taskId(), threshold);
        boolean pass = "DONE".equals(outcome.status()) && outcome.passRate() != null
                && outcome.passRate().doubleValue() >= threshold
                && ((List<?>) stepGate.get("failedSteps")).isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skipped", false);
        result.put("passed", pass);
        result.put("taskId", outcome.taskId());
        result.put("passRate", outcome.passRate() == null ? 0 : outcome.passRate().doubleValue());
        result.put("threshold", threshold);
        result.put("stepGate", stepGate);
        return result;
    }

    /** 手动触发预发布评测（需求 3.5：发布前触发，或手动触发）；异步任务，不阻断。 */
    private Map<String, Object> stepGate(Long taskId, double threshold) {
        List<EvalResult> results = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, taskId));
        Map<String, Object> summary = StepMetrics.summarize(results);
        List<String> failedSteps = StepMetrics.belowThreshold(summary, threshold);
        List<Map<String, Object>> failures = new ArrayList<>();
        for (EvalResult result : results) {
            String type = result.getStepType() == null ? "ANSWER"
                    : result.getStepType().toUpperCase(java.util.Locale.ROOT);
            if (!failedSteps.contains(type) || (result.getPassed() != null && result.getPassed() == 1)) continue;
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("stepType", type);
            failure.put("traceId", result.getTraceId());
            failure.put("datasetItemId", result.getDatasetItemId());
            failure.put("score", result.getScore());
            failure.put("reason", result.getJudgeReason());
            failure.put("stepDetails", result.getStepDetails());
            failures.add(failure);
            if (failures.size() >= 20) break;
        }
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("threshold", threshold);
        gate.put("steps", summary);
        gate.put("failedSteps", failedSteps);
        gate.put("failures", failures);
        return gate;
    }

    public Map<String, Object> runPreReleaseManual(Long agentId, Long datasetId, JwtPrincipal principal) {
        if (agentMapper.selectById(agentId) == null) throw new BusinessException(404, "Agent 不存在");
        Long dsId = datasetId;
        if (dsId == null) {
            EvalDataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                    .eq(EvalDataset::getAgentId, agentId).last("LIMIT 1"));
            if (ds == null) throw new BusinessException(400, "该 Agent 未绑定默认数据集，请指定数据集");
            dsId = ds.getId();
        }
        return createTask("预发布评测-" + LocalDate.now(), agentId, dsId, gateJudgeModel,
                gateThreshold(), Map.of("evalType", "PRE_RELEASE"), principal);
    }

    private double gateThreshold() {
        com.platform.system.entity.SysConfig cfg = sysConfigMapper.selectOne(
                new LambdaQueryWrapper<com.platform.system.entity.SysConfig>()
                        .eq(com.platform.system.entity.SysConfig::getConfigKey, "publish.gate-threshold"));
        if (cfg != null && cfg.getConfigValue() != null) {
            try { return Double.parseDouble(cfg.getConfigValue()); } catch (Exception e) {     log.debug("评测解析忽略异常：{}", e.getMessage()); }
        }
        return 0.8;
    }

    /** 评测任务结果摘要（门禁同步等待用；由内存直接返回，规避外层事务快照隔离读不到异步提交）。 */
    public record TaskOutcome(Long taskId, String status, BigDecimal avgScore, BigDecimal passRate) {
    }

    /**
     * 独立事务内创建并同步执行评测（门禁用）：挂起外层发布事务，评测写入独立提交，
     * 阻断发布回滚时评测记录/用量仍保留（供缓存复用与审计）。
     * 经 selfProvider 代理调用，保证 REQUIRES_NEW 生效。
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public TaskOutcome createAndExecuteExclusive(String name, Long agentId, Long datasetId,
                                                 String judgeModel, Double passThreshold,
                                                 String evalType, JwtPrincipal principal) {
        EvalTask t = createTaskRow(name, agentId, datasetId, judgeModel, passThreshold, evalType, null, principal);
        return execute(t.getId(), t.getTenantId(), passThreshold == null ? 0.6 : passThreshold);
    }

    /** 同步执行体：遍历样本 → Agent 执行 → Trace → 裁判 → 结果 → 汇总。 */
    TaskOutcome execute(Long taskId, Long tenantId, double passThreshold) {
        return TenantContext.runWith(tenantId, () -> {
            EvalTask t = taskMapper.selectById(taskId);
            if (t == null) return new TaskOutcome(taskId, "FAILED", null, null);
            EvalTask patch = new EvalTask();
            patch.setId(taskId);
            patch.setStatus("RUNNING");
            taskMapper.updateById(patch);

            List<EvalDatasetItem> items = itemMapper.selectList(
                    new LambdaQueryWrapper<EvalDatasetItem>().eq(EvalDatasetItem::getDatasetId, t.getDatasetId()));
            double scoreSum = 0;
            int passed = 0, done = 0, failed = 0;
            for (EvalDatasetItem item : items) {
                try {
                    evalOne(t, item, passThreshold);
                    done++;
                    EvalResult r = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                                    .eq(EvalResult::getTaskId, taskId)
                                    .eq(EvalResult::getDatasetItemId, item.getId()))
                            .stream().findFirst().orElse(null);
                    if (r != null && r.getScore() != null) {
                        scoreSum += r.getScore().doubleValue();
                        if (r.getPassed() != null && r.getPassed() == 1) passed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("样本评测失败 task={} item={}：{}", taskId, item.getId(), e.getMessage());
                }
                EvalTask progress = new EvalTask();
                progress.setId(taskId);
                progress.setCompletedCalls(done + failed);
                taskMapper.updateById(progress);
            }
            int total = done + failed;
            EvalTask finish = new EvalTask();
            finish.setId(taskId);
            finish.setStatus(failed == total ? "FAILED" : "DONE");
            finish.setCompletedCalls(total);
            finish.setAvgScore(done == 0 ? null
                    : BigDecimal.valueOf(scoreSum / done).setScale(4, RoundingMode.HALF_UP));
            finish.setPassRate(total == 0 ? null
                    : BigDecimal.valueOf((double) passed / total).setScale(4, RoundingMode.HALF_UP));
            taskMapper.updateById(finish);
            log.info("评测任务完成 taskId={} done={} failed={} avg={} passRate={}",
                    taskId, done, failed, finish.getAvgScore(), finish.getPassRate());
            return new TaskOutcome(taskId, finish.getStatus(), finish.getAvgScore(), finish.getPassRate());
        });
    }

    /** 步骤级确定性评测：检索/重排/改写，不调用最终回答模型与 LLM 裁判。 */
    private void evalStep(EvalTask task, EvalDatasetItem item, String stepType, String traceId) {
        long startedAt = System.currentTimeMillis();
        AgentRegistry agent = agentMapper.selectById(task.getAgentId());
        if (agent == null || agent.getOwnerId() == null) {
            throw new BusinessException(400, "步骤评测缺少 Agent 所有者");
        }
        KnowledgePermissionService.Viewer viewer = KnowledgePermissionService.Viewer.of(agent.getOwnerId(), null);
        String agentModel = null;
        if ("REWRITE".equals(stepType)) {
            Map<String, Object> agentConfig = "PRE_RELEASE".equals(task.getEvalType())
                    ? editStateConfig(task.getAgentId()) : publishedConfig(task.getAgentId());
            if (agentConfig.get("modelConfig") instanceof Map<?, ?> modelConfig) {
                agentModel = str(modelConfig.get("modelName"));
            }
        }
        StepEvaluationService.StepResult step = stepEvaluationService.evaluate(
                stepType, item.getQuestion(), item.getExpectedPayload(), item.getStepConfig(),
                agentModel, viewer);
        long latency = System.currentTimeMillis() - startedAt;
        com.platform.capability.runtime.TraceStepCollector collector =
                new com.platform.capability.runtime.TraceStepCollector();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("stepType", stepType);
        attributes.put("passed", step.passed());
        attributes.put("score", step.score());
        try {
            attributes.put("details", objectMapper.readValue(step.details(), Map.class));
        } catch (Exception ignored) {
            attributes.put("details", step.details());
        }
        collector.add(stepType.toLowerCase(java.util.Locale.ROOT), "eval-step",
                step.passed() ? "SUCCESS" : "ERROR", startedAt, System.currentTimeMillis(), attributes);
        traceService.record(task.getTenantId(), traceId, null, null, task.getAgentId(),
                task.getCreatedBy(), agent.getName(), "SUCCESS", "deterministic", 0, 0, latency, 0,
                TraceService.TraceExtra.eval(null, item.getQuestion(), step.actual(),
                        evalTraceTags(task, stepType.toLowerCase(java.util.Locale.ROOT))), null, collector.steps());
        EvalResult result = new EvalResult();
        result.setTaskId(task.getId());
        result.setDatasetItemId(item.getId());
        result.setTraceId(traceId);
        result.setMetricKey("step_" + stepType.toLowerCase(java.util.Locale.ROOT));
        result.setStepType(stepType);
        result.setStepPassed(step.passed() ? 1 : 0);
        result.setStepDetails(step.details());
        result.setAnswer(step.actual());
        result.setScore(BigDecimal.valueOf(step.score()).setScale(4, RoundingMode.HALF_UP));
        result.setPassed(step.passed() ? 1 : 0);
        result.setJudgeReason("确定性步骤校验");
        result.setLatencyMs(latency);
        result.setTokens(0);
        result.setTenantId(task.getTenantId());
        result.setCreatedAt(LocalDateTime.now());
        resultMapper.insert(result);
    }

    /** 单样本：Agent 执行（发布配置+RAG）→ trace → LLM 裁判 → eval_result。 */
    @SuppressWarnings("unchecked")
    private void evalOne(EvalTask task, EvalDatasetItem item, double passThreshold) {
        long startedAt = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String stepType = item.getStepType() == null ? "ANSWER"
                : item.getStepType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ANSWER".equals(stepType)) {
            evalStep(task, item, stepType, traceId);
            return;
        }

        // 1) Agent 执行：预发布门禁评「编辑态配置」（即将发布的版本）；
        //    其他类型评「最新发布版本快照」+ 变量默认值 + RAG
        AgentRegistry agent = agentMapper.selectById(task.getAgentId());
        Map<String, Object> config = "PRE_RELEASE".equals(task.getEvalType())
                ? editStateConfig(task.getAgentId()) : publishedConfig(task.getAgentId());
        String systemPrompt = str(config.get("systemPrompt"));
        systemPrompt = applyVarDefaults(systemPrompt, config.get("variables"));
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> kbBindings = knowledgeBindings(task.getAgentId());
        if (!kbBindings.isEmpty()) {
            // 评测以 Agent 所有者身份执行 RAG：仍按知识库可见性过滤（需求 3.9(2)）
            systemPrompt = chatService.augmentWithKnowledge(systemPrompt, item.getQuestion(), kbBindings, citations,
                    com.platform.knowledge.service.KnowledgePermissionService.Viewer.of(agent.getOwnerId(), null));
        }
        Map<String, Object> modelConfig = config.get("modelConfig") instanceof Map<?, ?> mc
                ? (Map<String, Object>) mc : Map.of();
        String modelName = str(modelConfig.getOrDefault("modelName", "deepseek-flash"));

        var spec = ChatClient.create(chatModelRouter.resolve(modelName)).prompt()
                .options(chatModelRouter.options(modelName, null, java.util.List.of()));
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);
        var agentResp = spec.user(item.getQuestion()).call().chatResponse();
        String answer = agentResp.getResult().getOutput().getText();
        Usage agentUsage = agentResp.getMetadata().getUsage();
        long agentTokens = (agentUsage == null ? 0 : agentUsage.getPromptTokens() + agentUsage.getCompletionTokens());
        long latency = System.currentTimeMillis() - startedAt;

        recordUsage(task.getTenantId(), task.getAgentId(), modelName,
                agentUsage == null ? 0 : agentUsage.getPromptTokens(),
                agentUsage == null ? 0 : agentUsage.getCompletionTokens());

        // 2) Trace（kind=eval；预发布门禁评编辑态配置无版本号，conversation 为空）
        AgentVersion latest = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, task.getAgentId())
                        .orderByDesc(AgentVersion::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        String traceVersion = "PRE_RELEASE".equals(task.getEvalType()) ? null
                : (latest == null ? null : latest.getVersion());
        traceService.record(task.getTenantId(), traceId, null, null, task.getAgentId(),
                task.getCreatedBy(), agent == null ? "agent" : agent.getName(), "SUCCESS",
                modelName, agentUsage == null ? 0 : agentUsage.getPromptTokens(),
                agentUsage == null ? 0 : agentUsage.getCompletionTokens(), latency, citations.size(),
                TraceService.TraceExtra.eval(traceVersion, item.getQuestion(), answer,
                        evalTraceTags(task, task.getEvalType() == null ? "once" : task.getEvalType().toLowerCase())));

        // 3) LLM 裁判
        long judgeStart = System.currentTimeMillis();
        String judgePrompt = """
                你是严格的评测裁判。根据评分标准对 AI 回答打分。
                【问题】%s
                【期望答案】%s
                【评分标准】%s
                【AI 回答】%s
                只输出严格 JSON：{"score": 0到1的小数, "reason": "30字内理由"}
                """.formatted(item.getQuestion() == null ? "" : item.getQuestion(),
                item.getExpectedAnswer() == null ? "（无）" : item.getExpectedAnswer(),
                item.getScoreCriteria() == null || item.getScoreCriteria().isBlank()
                        ? "回答须与期望答案事实一致、完整、不编造" : item.getScoreCriteria(),
                answer == null ? "" : answer);
        var judgeResp = ChatClient.create(chatModelRouter.resolve(task.getJudgeModel())).prompt()
                .options(OpenAiChatOptions.builder().model(task.getJudgeModel()).build())
                .system("你是评测裁判，只输出 JSON，不要任何其他文字。")
                .user(judgePrompt).call().chatResponse();
        String judgeText = judgeResp.getResult().getOutput().getText();
        Usage judgeUsage = judgeResp.getMetadata().getUsage();
        recordUsage(task.getTenantId(), null, task.getJudgeModel(),
                judgeUsage == null ? 0 : judgeUsage.getPromptTokens(),
                judgeUsage == null ? 0 : judgeUsage.getCompletionTokens());

        double score = 0;
        String reason = "解析失败";
        Matcher matcher = JSON_BLOCK.matcher(judgeText == null ? "" : judgeText);
        if (matcher.find()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(matcher.group(), Map.class);
                score = Double.parseDouble(String.valueOf(parsed.getOrDefault("score", "0")));
                reason = str(parsed.getOrDefault("reason", ""));
            } catch (Exception e) {
                log.debug("评测解析忽略异常：{}", e.getMessage());
            }
        }
        score = Math.max(0, Math.min(1, score));

        // 4) eval_result
        EvalResult r = new EvalResult();
        r.setTaskId(task.getId());
        r.setDatasetItemId(item.getId());
        r.setTraceId(traceId);
        r.setMetricKey("correctness");
        r.setStepType("ANSWER");
        r.setAnswer(answer);
        r.setScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
        r.setPassed(score >= passThreshold ? 1 : 0);
        r.setJudgeReason(reason);
        r.setLatencyMs(System.currentTimeMillis() - startedAt);
        r.setTokens((int) (agentTokens + (judgeUsage == null ? 0
                : judgeUsage.getPromptTokens() + judgeUsage.getCompletionTokens())));
        r.setTenantId(task.getTenantId());
        r.setCreatedAt(LocalDateTime.now());
        resultMapper.insert(r);
    }

    // ---------- 辅助 ----------

    private final com.platform.model.service.ChatModelRouter chatModelRouter;

    /** 门禁裁判模型：默认 DeepSeek；可切到平台专用本地模型做离线验收。 */
    @org.springframework.beans.factory.annotation.Value("${eval.gate-judge-model:deepseek-flash}")
    private String gateJudgeModel = "deepseek-flash";

    private Map<String, Object> publishedConfig(Long agentId) {
        AgentVersion v = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agentId)
                        .orderByDesc(AgentVersion::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (v == null) throw new BusinessException(400, "Agent 尚无发布版本");
        try {
            return objectMapper.readValue(v.getSnapshot(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 编辑态配置（agent_registry 当前字段），供预发布门禁评「即将发布」的版本。 */
    private Map<String, Object> editStateConfig(Long agentId) {
        AgentRegistry a = agentMapper.selectById(agentId);
        if (a == null) throw new BusinessException(404, "Agent 不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("systemPrompt", a.getSystemPrompt());
        m.put("modelConfig", fromJson(a.getModelConfig()));
        m.put("variables", fromJson(a.getVariables()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private String applyVarDefaults(String prompt, Object varDefs) {
        if (prompt == null || !(varDefs instanceof List<?> defs)) return prompt;
        for (Object o : defs) {
            if (o instanceof Map<?, ?> d && d.get("key") != null && d.get("defaultValue") != null) {
                prompt = prompt.replace("{{" + d.get("key") + "}}", String.valueOf(d.get("defaultValue")));
            }
        }
        return prompt;
    }

    private List<Map<String, Object>> knowledgeBindings(Long agentId) {
        List<com.platform.agent.entity.AgentKnowledgeBase> rows =
                agentKbMapper.selectList(new LambdaQueryWrapper<com.platform.agent.entity.AgentKnowledgeBase>()
                        .eq(com.platform.agent.entity.AgentKnowledgeBase::getAgentId, agentId)
                        .eq(com.platform.agent.entity.AgentKnowledgeBase::getEnabled, 1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("knowledgeBaseId", row.getKnowledgeBaseId());
            b.put("enabled", true);
            b.put("retrievalConfig", fromJson(row.getRetrievalConfig()));
            result.add(b);
        }
        return result;
    }

    private void recordUsage(Long tenantId, Long agentId, String modelName, long in, long out) {
        BillingUsage u = new BillingUsage();
        u.setTenantId(tenantId);
        u.setAgentId(agentId);
        u.setSource("eval");
        u.setModelName(modelName);
        u.setInputTokens(in);
        u.setOutputTokens(out);
        u.setCost(pricingService.cost(tenantId, modelName, in, out));
        u.setStatDate(LocalDate.now());
        u.setCreatedAt(LocalDateTime.now());
        billingMapper.insert(u);
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String jsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
