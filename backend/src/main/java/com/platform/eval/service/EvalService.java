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
    private final BillingUsageMapper billingMapper;
    private final com.platform.knowledge.service.EmbeddingService embeddingService;
    private final com.platform.knowledge.service.VectorRepository vectorRepository;
    private final ObjectMapper objectMapper;

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
        if (question == null || question.isBlank()) throw new BusinessException(400, "样本问题必填");
        EvalDatasetItem item = new EvalDatasetItem();
        item.setDatasetId(datasetId);
        item.setQuestion(question);
        item.setExpectedAnswer(expectedAnswer);
        item.setScoreCriteria(scoreCriteria);
        item.setTenantId(principal.tenantId());
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);
    }

    /** JSONL 批量导入：每行 {"question":..,"expectedAnswer":..,"scoreCriteria":..}。 */
    public int importItems(Long datasetId, String jsonl, JwtPrincipal principal) {
        int count = 0;
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> row = objectMapper.readValue(line.trim(), Map.class);
                addItem(datasetId, str(row.get("question")), str(row.get("expectedAnswer")),
                        str(row.get("scoreCriteria")), principal);
                count++;
            } catch (Exception e) {
                throw new BusinessException(400, "样本格式错误（第 " + (count + 1) + " 行）：" + e.getMessage());
            }
        }
        return count;
    }

    public void deleteItem(Long itemId) {
        itemMapper.deleteById(itemId);
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
            row.put("traceId", r.getTraceId());
            row.put("latencyMs", r.getLatencyMs());
            row.put("tokens", r.getTokens());
            rows.add(row);
        }
        detail.put("results", rows);
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
        t.setEvalType("ONCE");
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
        });
        return Map.of("taskId", t.getId());
    }

    /** 同步执行体：遍历样本 → Agent 执行 → Trace → 裁判 → 结果 → 汇总。 */
    void execute(Long taskId, Long tenantId, double passThreshold) {
        TenantContext.run(tenantId, () -> {
        EvalTask t = taskMapper.selectById(taskId);
        if (t == null) return;
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
        });
    }

    /** 单样本：Agent 执行（发布配置+RAG）→ trace → LLM 裁判 → eval_result。 */
    @SuppressWarnings("unchecked")
    private void evalOne(EvalTask task, EvalDatasetItem item, double passThreshold) {
        long startedAt = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");

        // 1) Agent 执行：发布版本配置 + 变量默认值 + RAG（与正式对话一致）
        AgentRegistry agent = agentMapper.selectById(task.getAgentId());
        Map<String, Object> config = publishedConfig(task.getAgentId());
        String systemPrompt = str(config.get("systemPrompt"));
        systemPrompt = applyVarDefaults(systemPrompt, config.get("variables"));
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> kbBindings = knowledgeBindings(task.getAgentId());
        if (!kbBindings.isEmpty()) {
            systemPrompt = chatService.augmentWithKnowledge(systemPrompt, item.getQuestion(), kbBindings, citations);
        }
        Map<String, Object> modelConfig = config.get("modelConfig") instanceof Map<?, ?> mc
                ? (Map<String, Object>) mc : Map.of();
        String modelName = str(modelConfig.getOrDefault("modelName", "deepseek-flash"));

        var spec = ChatClient.create(chatModel()).prompt()
                .options(OpenAiChatOptions.builder().model(modelName).build());
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);
        var agentResp = spec.user(item.getQuestion()).call().chatResponse();
        String answer = agentResp.getResult().getOutput().getText();
        Usage agentUsage = agentResp.getMetadata().getUsage();
        long agentTokens = (agentUsage == null ? 0 : agentUsage.getPromptTokens() + agentUsage.getCompletionTokens());
        long latency = System.currentTimeMillis() - startedAt;

        recordUsage(task.getTenantId(), task.getAgentId(), modelName,
                agentUsage == null ? 0 : agentUsage.getPromptTokens(),
                agentUsage == null ? 0 : agentUsage.getCompletionTokens());

        // 2) Trace（source=eval；conversation 为空）
        AgentVersion latest = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, task.getAgentId())
                        .orderByDesc(AgentVersion::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        traceService.record(task.getTenantId(), traceId, null, null, task.getAgentId(),
                task.getCreatedBy(), agent == null ? "agent" : agent.getName(), "SUCCESS",
                modelName, agentUsage == null ? 0 : agentUsage.getPromptTokens(),
                agentUsage == null ? 0 : agentUsage.getCompletionTokens(), latency, citations.size());

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
        var judgeResp = ChatClient.create(chatModel()).prompt()
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
            } catch (Exception ignored) {
            }
        }
        score = Math.max(0, Math.min(1, score));

        // 4) eval_result
        EvalResult r = new EvalResult();
        r.setTaskId(task.getId());
        r.setDatasetItemId(item.getId());
        r.setTraceId(traceId);
        r.setMetricKey("correctness");
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

    private org.springframework.ai.chat.model.ChatModel chatModel() {
        return chatModelBean;
    }

    private final org.springframework.ai.chat.model.ChatModel chatModelBean;

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
}
