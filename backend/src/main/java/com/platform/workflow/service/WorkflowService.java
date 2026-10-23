package com.platform.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.service.HybridRetrievalService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.model.service.ChatModelRouter;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import com.platform.workflow.entity.AgentWorkflow;
import com.platform.workflow.entity.AgentWorkflowVersion;
import com.platform.workflow.entity.WorkflowApproval;
import com.platform.workflow.entity.WorkflowNodeRun;
import com.platform.workflow.entity.WorkflowRun;
import com.platform.workflow.mapper.AgentWorkflowMapper;
import com.platform.workflow.mapper.WorkflowApprovalMapper;
import com.platform.workflow.mapper.WorkflowNodeRunMapper;
import com.platform.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 工作流引擎 MVP：
 * 节点类型 START / LLM / AGENT / CONDITION / APPROVAL / END；顺序执行，审批节点暂停，审批后恢复。
 * 运行快照保存 definition，编辑工作流不影响已开始的运行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final AgentWorkflowMapper workflowMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowApprovalMapper approvalMapper;
    private final com.platform.workflow.mapper.AgentWorkflowVersionMapper versionMapper;
    private final AgentRegistryMapper agentMapper;
    private final com.platform.agent.mapper.AgentVersionMapper agentVersionMapper;
    private final com.platform.agent.service.ChatService chatService;
    private final ChatModelRouter modelRouter;
    private final com.platform.agent.service.ContextWindowService contextWindow;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;
    private final AuditService auditService;
    private final com.platform.capability.runtime.ToolRuntimeService toolRuntime;
    private final HybridRetrievalService hybridRetrieval;
    private final KnowledgePermissionService permissionService;
    private final com.platform.system.service.NotificationService notificationService;
    private final com.platform.system.mapper.SysUserMapper userMapper;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Qualifier("llmTaskExecutor")
    private final org.springframework.core.task.TaskExecutor llmExecutor;

    private static final int MAX_STEPS = 50;

    // ---------- 配置 ----------

    public Map<String, Object> get(Long agentId) {
        AgentRegistry agent = requireAgent(agentId);
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", wf != null);
        m.put("agentId", agentId);
        m.put("published", false);
        m.put("hasUnpublishedChanges", false);
        m.put("agentName", agent.getName());
        if (wf == null) {
            m.put("name", agent.getName() + " 工作流");
            m.put("status", "ENABLED");
            m.put("definition", defaultDefinition());
        } else {
            m.put("id", wf.getId());
            m.put("name", wf.getName());
            m.put("status", wf.getStatus());
            m.put("definition", parse(wf.getDefinition()));
            m.put("published", wf.getPublishedDefinition() != null);
            m.put("publishedVersion", wf.getPublishedVersion());
            m.put("hasUnpublishedChanges", wf.getPublishedDefinition() != null
                    && !wf.getPublishedDefinition().equals(wf.getDefinition()));
            m.put("updatedAt", wf.getUpdatedAt());
        }
        return m;
    }

    public Map<String, Object> save(Long agentId, Map<String, Object> req, JwtPrincipal principal) {
        AgentRegistry agent = requireAgent(agentId);
        Long tenantId = agent.getTenantId();
        String name = str(req.get("name"));
        if (name == null || name.isBlank()) name = agent.getName() + " 工作流";
        String status = "DISABLED".equalsIgnoreCase(str(req.get("status"))) ? "DISABLED" : "ENABLED";
        Object rawDefinition = req.get("definition");
        Map<String, Object> definition = rawDefinition instanceof Map<?, ?> m
                ? castMap(m) : parse(str(rawDefinition));
        normalizeGraph(definition);
        validate(definition);
        String json = json(definition);

        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        if (wf == null) {
            wf = new AgentWorkflow();
            wf.setAgentId(agentId);
            wf.setTenantId(tenantId);
            wf.setCreatedBy(principal.userId());
            wf.setCreatedAt(LocalDateTime.now());
        }
        wf.setName(name.trim());
        wf.setStatus(status);
        wf.setDefinition(json);
        wf.setUpdatedAt(LocalDateTime.now());
        if (wf.getId() == null) workflowMapper.insert(wf);
        else workflowMapper.updateById(wf);
        auditService.record(principal.userId(), "workflow.save", "agent_workflow",
                String.valueOf(agentId), "SUCCESS", Map.of("name", wf.getName(), "status", status));
        return get(agentId);
    }

    /** 发布当前草稿为不可变版本；运行始终优先使用已发布定义。 */
    public Map<String, Object> publish(Long agentId, JwtPrincipal principal) {
        AgentRegistry agent = requireAgent(agentId);
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        if (wf == null) throw new BusinessException(400, "该 Agent 尚未配置工作流");
        validate(parse(wf.getDefinition()));
        long count = versionMapper.selectCount(new LambdaQueryWrapper<AgentWorkflowVersion>()
                .eq(AgentWorkflowVersion::getWorkflowId, wf.getId()));
        String version = "V" + (count + 1);
        AgentWorkflowVersion v = new AgentWorkflowVersion();
        v.setWorkflowId(wf.getId());
        v.setTenantId(wf.getTenantId());
        v.setVersion(version);
        v.setName(wf.getName());
        v.setDefinition(wf.getDefinition());
        v.setCreatedBy(principal.userId());
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        wf.setPublishedVersion(version);
        wf.setPublishedDefinition(wf.getDefinition());
        wf.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(wf);
        auditService.record(principal.userId(), "workflow.publish", "agent_workflow",
                String.valueOf(agentId), "SUCCESS", Map.of("version", version));
        return Map.of("version", version, "name", wf.getName());
    }

    public List<AgentWorkflowVersion> versions(Long agentId) {
        requireAgent(agentId);
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        if (wf == null) return List.of();
        return versionMapper.selectList(new LambdaQueryWrapper<AgentWorkflowVersion>()
                .eq(AgentWorkflowVersion::getWorkflowId, wf.getId())
                .orderByDesc(AgentWorkflowVersion::getId));
    }

    /** 回滚到历史版本：恢复草稿并发布为新版本（保留历史链）。 */
    public Map<String, Object> rollback(Long agentId, String version, JwtPrincipal principal) {
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        if (wf == null) throw new BusinessException(400, "该 Agent 尚未配置工作流");
        AgentWorkflowVersion target = versionMapper.selectOne(new LambdaQueryWrapper<AgentWorkflowVersion>()
                .eq(AgentWorkflowVersion::getWorkflowId, wf.getId())
                .eq(AgentWorkflowVersion::getVersion, version)
                .last("LIMIT 1"));
        if (target == null) throw new BusinessException(404, "工作流版本不存在");
        wf.setName(target.getName());
        wf.setDefinition(target.getDefinition());
        wf.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(wf);
        Map<String, Object> result = publish(agentId, principal);
        auditService.record(principal.userId(), "workflow.rollback", "agent_workflow",
                String.valueOf(agentId), "SUCCESS", Map.of("from", version,
                        "newVersion", String.valueOf(result.get("version"))));
        return result;
    }

    public void delete(Long agentId, JwtPrincipal principal) {
        requireAgent(agentId);
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        if (wf != null) {
            versionMapper.delete(new LambdaQueryWrapper<AgentWorkflowVersion>()
                    .eq(AgentWorkflowVersion::getWorkflowId, wf.getId()));
        }
        workflowMapper.delete(new LambdaQueryWrapper<AgentWorkflow>().eq(AgentWorkflow::getAgentId, agentId));
        auditService.record(principal.userId(), "workflow.delete", "agent_workflow",
                String.valueOf(agentId), "SUCCESS", Map.of());
    }

    // ---------- 模板 ----------

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(template("simple_qa", "简单问答", "START → LLM → END",
                nodes(node("start", "START", "开始", "llm1", null, null),
                        node("llm1", "LLM", "回答", "end", "根据用户输入简洁准确地回答。", null),
                        node("end", "END", "结束", null, null, null))));
        list.add(template("rag_qa", "知识库问答", "START → KNOWLEDGE → LLM → END（需补充知识库）",
                nodes(node("start", "START", "开始", "kb1", null, null),
                        knowledge("kb1", "llm1"),
                        node("llm1", "LLM", "依据知识库回答", "end", "依据知识库检索结果回答用户，不知道就说明不知道。", null),
                        node("end", "END", "结束", null, null, null))));
        list.add(template("approval_flow", "人工审批流", "START → LLM → APPROVAL(ADMIN) → LLM → END",
                nodes(node("start", "START", "开始", "llm1", null, null),
                        node("llm1", "LLM", "初步处理", "appr1", "根据用户输入给出处理建议，简洁。", null),
                        approval("appr1", "llm2"),
                        node("llm2", "LLM", "最终回复", "end", "审批已通过，请给出最终答复。", null),
                        node("end", "END", "结束", null, null, null))));
        list.add(template("parallel_flow", "并行分支", "START → PARALLEL(两个 LLM 分支) → JOIN → LLM → END",
                nodes(node("start", "START", "开始", "par1", null, null),
                        parallel("par1", new String[]{"a1", "b1"}, "join1"),
                        node("a1", "LLM", "分支A", "join1", "从方案 A 的角度分析问题，简洁输出。", null),
                        node("b1", "LLM", "分支B", "join1", "从方案 B 的角度分析问题，简洁输出。", null),
                        join("join1", "llm2"),
                        node("llm2", "LLM", "汇总", "end", "汇总两个分支的结果，给出最终结论。", null),
                        node("end", "END", "结束", null, null, null))));
        list.add(template("tool_flow", "工具调用", "START → TOOL → LLM → END（需补充工具）",
                nodes(node("start", "START", "开始", "tool1", null, null),
                        tool("tool1", null),
                        node("llm1", "LLM", "基于工具结果回答", "end", "结合工具结果回答用户，简洁。", null),
                        node("end", "END", "结束", null, null, null))));
        return list;
    }

    private Map<String, Object> template(String id, String name, String description, List<Map<String, Object>> nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("definition", Map.of("nodes", nodes));
        return m;
    }

    private List<Map<String, Object>> nodes(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }

    private Map<String, Object> knowledge(String id, String next) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "KNOWLEDGE");
        m.put("name", "知识库检索");
        m.put("mode", "hybrid");
        m.put("topK", 5);
        m.put("queryTemplate", "{{input}}");
        m.put("next", next);
        return m;
    }

    private Map<String, Object> approval(String id, String next) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "APPROVAL");
        m.put("name", "人工审批");
        m.put("title", "人工审批");
        m.put("requiredRole", "ADMIN");
        m.put("timeoutMinutes", 120);
        m.put("next", next);
        return m;
    }

    private Map<String, Object> parallel(String id, String[] branches, String joinNode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "PARALLEL");
        m.put("name", "并行分支");
        m.put("branches", List.of(branches));
        m.put("joinNode", joinNode);
        m.put("strategy", "BEST_EFFORT");
        m.put("next", joinNode);
        return m;
    }

    private Map<String, Object> join(String id, String next) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "JOIN");
        m.put("name", "汇合");
        m.put("next", next);
        return m;
    }

    private Map<String, Object> tool(String id, Long toolId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "TOOL");
        m.put("name", "工具调用");
        m.put("argsTemplate", "{}");
        if (toolId != null) m.put("toolId", toolId);
        m.put("next", "llm1");
        return m;
    }

    // ---------- 运行 ----------

    public Map<String, Object> run(Long agentId, String input, JwtPrincipal principal) {
        requireAgent(agentId);
        if (input == null || input.isBlank()) throw new BusinessException(400, "运行输入不能为空");
        if (input.length() > 10000) throw new BusinessException(400, "运行输入过长（上限 10000 字）");
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        if (wf == null) throw new BusinessException(400, "该 Agent 尚未配置工作流");
        if (!"ENABLED".equals(wf.getStatus())) throw new BusinessException(400, "工作流已停用");
        String runDefinition = wf.getPublishedDefinition() != null ? wf.getPublishedDefinition() : wf.getDefinition();
        Map<String, Object> normalizedDefinition = parse(runDefinition);
        normalizeGraph(normalizedDefinition);
        validate(normalizedDefinition);
        runDefinition = json(normalizedDefinition);

        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(wf.getId());
        run.setAgentId(agentId);
        run.setTenantId(wf.getTenantId());
        run.setDefinition(runDefinition);
        run.setInputText(input.trim());
        run.setOutputText(input.trim());
        run.setContextJson(json(Map.of("outputs", List.of(), "state", Map.of())));
        run.setStatus("RUNNING");
        run.setUserId(principal.userId());
        run.setInputTokens(0L);
        run.setOutputTokens(0L);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(run);
        auditService.record(principal.userId(), "workflow.run", "workflow_run",
                String.valueOf(run.getId()), "SUCCESS", Map.of("agentId", agentId));
        execute(run, "start", principal);
        return runView(runMapper.selectById(run.getId()));
    }

    public Map<String, Object> decide(Long approvalId, boolean approve, String comment, JwtPrincipal principal) {
        WorkflowApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null || !Objects.equals(approval.getTenantId(), TenantContext.getTenantId())) {
            throw new BusinessException(404, "审批任务不存在");
        }
        if (!"PENDING".equals(approval.getStatus())) throw new BusinessException(409, "该审批已处理");
        WorkflowRun run = runMapper.selectById(approval.getRunId());
        if (run == null) throw new BusinessException(404, "运行记录不存在");
        if (run.getUserId() != null && run.getUserId().equals(principal.userId()) && !principal.platformAdmin()) {
            throw new BusinessException(403, "不能审批自己发起的工作流运行，请由其他有权限成员处理");
        }
        if (approval.getExpireAt() != null && approval.getExpireAt().isBefore(LocalDateTime.now())) {
            expireOne(approval);
            throw new BusinessException(409, "该审批已超时，运行已终止");
        }
        String requiredRole = approval.getRequiredRole();
        if (requiredRole != null && !requiredRole.isBlank()
                && !principal.platformAdmin()
                && !principal.roles().contains("OWNER")
                && !principal.roles().contains(requiredRole)) {
            throw new BusinessException(403, "需要 " + requiredRole + " 角色才能处理该审批");
        }
        int updated = approvalMapper.update(null, new LambdaUpdateWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getId, approvalId)
                .eq(WorkflowApproval::getStatus, "PENDING")
                .set(WorkflowApproval::getStatus, approve ? "APPROVED" : "REJECTED")
                .set(WorkflowApproval::getDecidedBy, principal.userId())
                .set(WorkflowApproval::getDecidedAt, LocalDateTime.now())
                .set(WorkflowApproval::getComment, comment));
        if (updated == 0) throw new BusinessException(409, "该审批已处理");
        if (!"WAITING_APPROVAL".equals(run.getStatus())) {
            throw new BusinessException(409, "运行状态不是待审批");
        }
        WorkflowNodeRun nodeRun = nodeRunMapper.selectOne(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, run.getId())
                .eq(WorkflowNodeRun::getNodeId, approval.getNodeId())
                .orderByDesc(WorkflowNodeRun::getId).last("LIMIT 1"));
        if (approve) {
            if (nodeRun != null) {
                nodeRun.setStatus("SUCCESS");
                nodeRun.setEndedAt(LocalDateTime.now());
                nodeRun.setOutputText("APPROVED" + (comment == null || comment.isBlank() ? "" : "：" + comment));
                nodeRunMapper.updateById(nodeRun);
            }
            Map<String, Object> definition = parse(run.getDefinition());
            Map<String, Object> node = findNode(definition, approval.getNodeId());
            Map<String, Object> approvalPayload = parseMap(approval.getPayload());
            if (Boolean.TRUE.equals(approvalPayload.get("branchApprovalGate"))
                    && approvalPayload.get("approvalNodeIds") instanceof List<?> branchApprovalIds) {
                Map<String, Object> ctx = parseMap(run.getContextJson());
                Map<String, Object> approved = new LinkedHashMap<>();
                if (ctx.get("branchApprovals") instanceof Map<?, ?> old) {
                    for (Map.Entry<?, ?> e : old.entrySet()) approved.put(String.valueOf(e.getKey()), e.getValue());
                }
                for (Object id : branchApprovalIds) approved.put(String.valueOf(id), true);
                ctx.put("branchApprovals", approved);
                run.setContextJson(json(ctx));
                run.setStatus("RUNNING");
                run.setCurrentNode(str(approvalPayload.get("parallelNodeId")));
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
                auditService.record(principal.userId(), "workflow.parallel_resume", "workflow_approval",
                        String.valueOf(approvalId), "SUCCESS",
                        Map.of("runId", run.getId(), "parallelNodeId",
                                str(approvalPayload.get("parallelNodeId"))));
                execute(run, str(approvalPayload.get("parallelNodeId")), principal);
                return runView(runMapper.selectById(run.getId()));
            }
            if (approvalPayload.get("remainingItems") instanceof List<?> remaining
                    && !remaining.isEmpty() && approval.getNodeId() != null) {
                List<String> items = remaining.stream().map(String::valueOf).toList();
                node.put("items", items);
                run.setDefinition(json(definition));
                run.setStatus("RUNNING");
                run.setCurrentNode(approval.getNodeId());
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
                auditService.record(principal.userId(), "workflow.loop_resume", "workflow_approval",
                        String.valueOf(approvalId), "SUCCESS",
                        Map.of("runId", run.getId(), "remaining", items.size()));
                execute(run, approval.getNodeId(), principal);
                return runView(runMapper.selectById(run.getId()));
            }
            String next = str(node.get("next"));
            run.setStatus("RUNNING");
            run.setCurrentNode(next);
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            auditService.record(principal.userId(), "workflow.approve", "workflow_approval",
                    String.valueOf(approvalId), "SUCCESS", Map.of("runId", run.getId()));
            notificationService.notify(run.getTenantId(),
                    run.getUserId() == null ? java.util.List.of() : java.util.List.of(run.getUserId()),
                    "workflow_result", "审批已通过：" + approval.getTitle(),
                    "工作流 " + run.getId() + " 已继续执行",
                    "/agent/edit/" + run.getAgentId());
            execute(run, next, principal);
        } else {
            if (nodeRun != null) {
                nodeRun.setStatus("REJECTED");
                nodeRun.setEndedAt(LocalDateTime.now());
                nodeRun.setOutputText("REJECTED" + (comment == null || comment.isBlank() ? "" : "：" + comment));
                nodeRunMapper.updateById(nodeRun);
            }
            run.setStatus("REJECTED");
            run.setErrorMessage(comment == null ? "人工审批拒绝" : comment);
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            auditService.record(principal.userId(), "workflow.reject", "workflow_approval",
                    String.valueOf(approvalId), "SUCCESS", Map.of("runId", run.getId()));
            notificationService.notify(run.getTenantId(),
                    run.getUserId() == null ? java.util.List.of() : java.util.List.of(run.getUserId()),
                    "workflow_result", "审批已拒绝：" + approval.getTitle(),
                    comment == null ? "工作流已拒绝" : comment,
                    "/agent/edit/" + run.getAgentId());
        }
        return runView(runMapper.selectById(run.getId()));
    }

    private void execute(WorkflowRun run, String startNode, JwtPrincipal principal) {
        try {
            Map<String, Object> definition = parse(run.getDefinition());
            Map<String, Map<String, Object>> nodes = nodeMap(definition);
            String nodeId = startNode == null || startNode.isBlank() ? "start" : startNode;
            String lastOutput = run.getOutputText();
            List<Map<String, Object>> outputs = new ArrayList<>();
            Map<String, Object> state = new LinkedHashMap<>();
            if (run.getContextJson() != null) {
                Map<String, Object> ctx = parseMap(run.getContextJson());
                if (ctx.get("outputs") instanceof List<?> l) {
                    for (Object o : l) if (o instanceof Map<?, ?> m) outputs.add(castMap(m));
                }
                if (ctx.get("state") instanceof Map<?, ?> sm) state.putAll(castMap(sm));
            }
            int steps = 0;
            while (steps++ < MAX_STEPS) {
                Map<String, Object> node = nodes.get(nodeId);
                if (node == null) {
                    fail(run, "节点不存在：" + nodeId);
                    return;
                }
                String type = str(node.getOrDefault("type", "LLM")).toUpperCase();
                String name = str(node.getOrDefault("name", nodeId));
                if ("END".equals(type)) {
                    run.setStatus("SUCCESS");
                    run.setCurrentNode(nodeId);
                    run.setOutputText(lastOutput);
                    run.setUpdatedAt(LocalDateTime.now());
                    run.setContextJson(contextJson(outputs, state));
                    runMapper.updateById(run);
                    return;
                }
                WorkflowNodeRun nr = new WorkflowNodeRun();
                nr.setRunId(run.getId());
                nr.setTenantId(run.getTenantId());
                nr.setNodeId(nodeId);
                nr.setNodeType(type);
                nr.setName(name);
                nr.setStatus("RUNNING");
                nr.setInputText(truncate(lastOutput, 8000));
                nr.setStartedAt(LocalDateTime.now());
                nodeRunMapper.insert(nr);

                String output;
                String next;
                boolean nodeFailed = false;
                SimpleResult simple = isSimpleNode(type)
                        ? executeWithPolicy(type, node, run, outputs, lastOutput, principal,
                        run.getInputText()) : null;
                if (simple != null) {
                    output = simple.output();
                    next = simple.next();
                    nodeFailed = simple.failed();
                    run.setInputTokens(nz(run.getInputTokens()) + simple.inputTokens());
                    run.setOutputTokens(nz(run.getOutputTokens()) + simple.outputTokens());
                } else if ("STATE".equals(type)) {
                    String key = str(node.get("stateKey"));
                    if (key == null || key.isBlank()) throw new BusinessException(400, "STATE 节点必须配置 stateKey");
                    String template = str(node.get("valueTemplate"));
                    String value = template == null || template.isBlank() ? lastOutput
                            : applyStateTemplate(template, run, state, lastOutput);
                    state.put(key, value == null ? "" : value);
                    output = value == null ? "" : value;
                    next = str(node.get("next"));
                } else if ("START".equals(type)) {
                    output = run.getInputText();
                    next = str(node.get("next"));
                } else if ("LOOP".equals(type)) {
                    try {
                        LoopResult loop = executeLoop(node, run, nodes, outputs, lastOutput, principal, state);
                        output = loop.output();
                        next = str(node.get("next"));
                        state.putAll(loop.state());
                        run.setInputTokens(nz(run.getInputTokens()) + loop.inputTokens());
                        run.setOutputTokens(nz(run.getOutputTokens()) + loop.outputTokens());
                    } catch (LoopLimitExceededException ex) {
                        output = ex.partialOutput + "\n" + ex.getMessage()
                                + "；剩余项预览：" + ex.preview;
                        run.setInputTokens(nz(run.getInputTokens()) + ex.inputTokens);
                        run.setOutputTokens(nz(run.getOutputTokens()) + ex.outputTokens);
                        // 已执行一批后暂停；审批通过后把剩余项写回 LOOP 并继续，可能再产生下一批审批
                        WorkflowApproval approval = new WorkflowApproval();
                        approval.setRunId(run.getId());
                        approval.setAgentId(run.getAgentId());
                        approval.setTenantId(run.getTenantId());
                        approval.setNodeId(nodeId);
                        approval.setTitle(str(node.getOrDefault("limitTitle", "循环次数超限，需人工确认")));
                        String requiredRole = str(node.get("requiredRole"));
                        approval.setRequiredRole(requiredRole);
                        int timeoutMinutes = toInt(node.get("timeoutMinutes"));
                        if (timeoutMinutes > 0) {
                            approval.setExpireAt(LocalDateTime.now().plusMinutes(timeoutMinutes));
                        }
                        Map<String, Object> approvalPayload = new LinkedHashMap<>();
                        approvalPayload.put("message", output);
                        approvalPayload.put("remainingItems", ex.remainingItems);
                        approvalPayload.put("loopNodeId", nodeId);
                        approval.setPayload(json(approvalPayload));
                        approval.setStatus("PENDING");
                        approval.setCreatedAt(LocalDateTime.now());
                        approvalMapper.insert(approval);
                        java.util.List<Long> recipients = requiredRole == null || requiredRole.isBlank()
                                ? userMapper.findOwnerAdminUserIds(run.getTenantId())
                                : userMapper.findUserIdsByRole(run.getTenantId(), requiredRole);
                        if (recipients.isEmpty()) recipients = userMapper.findOwnerAdminUserIds(run.getTenantId());
                        notificationService.notify(run.getTenantId(), recipients, "workflow_approval",
                                "循环超限待处理：" + approval.getTitle(), output, "/approvals");
                        nr.setStatus("WAITING");
                        nr.setOutputText(truncate(output, 20000));
                        nr.setEndedAt(LocalDateTime.now());
                        nodeRunMapper.updateById(nr);
                        List<Map<String, Object>> waitingOutputs = new ArrayList<>(outputs);
                        waitingOutputs.add(Map.of("nodeId", nodeId, "name", name,
                                "output", truncate(output, 1500)));
                        run.setStatus("WAITING_APPROVAL");
                        run.setCurrentNode(nodeId);
                        run.setOutputText(truncate(output, 20000));
                        run.setUpdatedAt(LocalDateTime.now());
                        run.setContextJson(contextJson(waitingOutputs, state));
                        runMapper.updateById(run);
                        auditService.record(run.getUserId(), "workflow.loop_limit", "workflow_run",
                                String.valueOf(run.getId()), "WAITING_APPROVAL",
                                Map.of("nodeId", nodeId, "requested", ex.requested,
                                        "limit", ex.limit, "approvalId", approval.getId()));
                        return;
                    }
                } else if ("PARALLEL".equals(type)) {
                    if (!ensureParallelApprovals(node, nodeId, run, nodes, nr, principal)) return;
                    ParallelResult result = executeParallel(node, run, nodes, outputs, principal, state);
                    output = result.output();
                    state.putAll(result.state());
                    run.setInputTokens(nz(run.getInputTokens()) + result.inputTokens());
                    run.setOutputTokens(nz(run.getOutputTokens()) + result.outputTokens());
                    next = str(node.get("joinNode"));
                } else if ("JOIN".equals(type)) {
                    output = lastOutput == null ? run.getInputText() : lastOutput;
                    next = str(node.get("next"));
                } else if ("CONDITION".equals(type)) {
                    boolean matched = evaluate(node, lastOutput == null ? run.getInputText() : lastOutput);
                    output = matched ? "true" : "false";
                    next = matched ? str(node.get("trueNext")) : str(node.get("falseNext"));
                } else if ("APPROVAL".equals(type)) {
                    output = lastOutput == null ? run.getInputText() : lastOutput;
                    WorkflowApproval approval = new WorkflowApproval();
                    approval.setRunId(run.getId());
                    approval.setAgentId(run.getAgentId());
                    approval.setTenantId(run.getTenantId());
                    approval.setNodeId(nodeId);
                    String approvalTitle = str(node.getOrDefault("title", name));
                    approval.setTitle(approvalTitle);
                    String requiredRole = str(node.get("requiredRole"));
                    approval.setRequiredRole(requiredRole);
                    int timeoutMinutes = toInt(node.get("timeoutMinutes"));
                    if (timeoutMinutes > 0) {
                        approval.setExpireAt(LocalDateTime.now().plusMinutes(timeoutMinutes));
                    }
                    approval.setPayload(truncate(output, 20000));
                    approval.setStatus("PENDING");
                    approval.setCreatedAt(LocalDateTime.now());
                    approvalMapper.insert(approval);
                    java.util.List<Long> recipients = requiredRole == null || requiredRole.isBlank()
                            ? userMapper.findOwnerAdminUserIds(run.getTenantId())
                            : userMapper.findUserIdsByRole(run.getTenantId(), requiredRole);
                    if (recipients.isEmpty()) recipients = userMapper.findOwnerAdminUserIds(run.getTenantId());
                    notificationService.notify(run.getTenantId(), recipients, "workflow_approval",
                            "待审批：" + approvalTitle,
                            "Agent 工作流 " + run.getId() + " 需要审批；输入：" + truncate(run.getInputText(), 200),
                            "/approvals");
                    nr.setStatus("WAITING");
                    nr.setOutputText(truncate(output, 20000));
                    nr.setEndedAt(LocalDateTime.now());
                    nodeRunMapper.updateById(nr);
                    run.setStatus("WAITING_APPROVAL");
                    run.setCurrentNode(nodeId);
                    run.setOutputText(output);
                    run.setUpdatedAt(LocalDateTime.now());
                    run.setContextJson(contextJson(outputs, state));
                    runMapper.updateById(run);
                    return;
                } else {
                    fail(run, "不支持的节点类型：" + type);
                    return;
                }

                nr.setStatus(nodeFailed ? "FAILED" : "SUCCESS");
                nr.setOutputText(truncate(output, 20000));
                nr.setEndedAt(LocalDateTime.now());
                nodeRunMapper.updateById(nr);
                outputs.add(Map.of("nodeId", nodeId, "name", name, "output", truncate(output, 2000)));
                lastOutput = output;
                run.setOutputText(output);
                run.setCurrentNode(next);
                run.setUpdatedAt(LocalDateTime.now());
                run.setContextJson(contextJson(outputs, state));
                runMapper.updateById(run);
                if (next == null || next.isBlank()) {
                    run.setStatus("SUCCESS");
                    run.setUpdatedAt(LocalDateTime.now());
                    runMapper.updateById(run);
                    return;
                }
                nodeId = next;
            }
            fail(run, "工作流超过最大节点执行次数（" + MAX_STEPS + "）");
        } catch (Exception e) {
            log.warn("工作流执行失败 run={}：{}", run.getId(), e.getMessage());
            fail(run, rootMessage(e));
        }
    }

    private Map<String, Object> agentConfig(AgentRegistry agent) {
        com.platform.agent.entity.AgentVersion published = agentVersionMapper.selectList(
                        new LambdaQueryWrapper<com.platform.agent.entity.AgentVersion>()
                                .eq(com.platform.agent.entity.AgentVersion::getAgentId, agent.getId())
                                .orderByDesc(com.platform.agent.entity.AgentVersion::getId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (published != null && published.getSnapshot() != null) {
            return parseMap(published.getSnapshot());
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("systemPrompt", agent.getSystemPrompt());
        config.put("modelConfig", parseMap(agent.getModelConfig()));
        config.put("agentCapabilities", parseMap(agent.getAgentCapabilities()));
        config.put("variables", parseObject(agent.getVariables()));
        return config;
    }

    /**
     * Agent handoff：调用目标 Agent 的完整发布配置（工具/RAG/技能/子 Agent），
     * 输出作为当前节点的 lastOutput，并进入后续节点的共享「前序节点结果」上下文。
     */
    private SimpleResult handoff(Map<String, Object> node, WorkflowRun run, JwtPrincipal principal,
                                 String input) {
        Long targetId = toLong(node.get("agentId"));
        if (targetId == null) throw new BusinessException(400, "HANDOFF 节点必须选择 Agent");
        AgentRegistry target = agentMapper.selectById(targetId);
        if (target == null || "DELETED".equals(target.getStatus())) {
            throw new BusinessException(400, "HANDOFF 目标 Agent 不可用");
        }
        Map<String, Object> config = agentConfig(target);
        String template = str(node.get("inputTemplate"));
        Map<String, Object> state = stateOf(run);
        String message = template == null || template.isBlank() ? input : applyStateTemplate(template, run, state, input);
        if (message == null || message.isBlank()) message = "请接手当前任务并给出结果。";
        var viewer = KnowledgePermissionService.Viewer.of(principal.userId(), principal.roles());
        Map<String, Object> result;
        try {
            result = chatService.debugChat(config, message, viewer, principal);
        } catch (Exception e) {
            throw new BusinessException(502, "HANDOFF 调用失败：" + rootMessage(e));
        }
        String answer = str(result.get("answer"));
        long in = 0, out = 0;
        if (result.get("usage") instanceof Map<?, ?> usage) {
            in = toLong(usage.get("inputTokens")) == null ? 0 : toLong(usage.get("inputTokens"));
            out = toLong(usage.get("outputTokens")) == null ? 0 : toLong(usage.get("outputTokens"));
        }

        // 可选 A→B→A：目标 Agent 完成后回传给指定 Agent，形成真实 handoff 往返
        Long returnId = toLong(node.get("returnAgentId"));
        if (returnId != null) {
            AgentRegistry returnAgent = agentMapper.selectById(returnId);
            if (returnAgent == null || "DELETED".equals(returnAgent.getStatus())) {
                throw new BusinessException(400, "HANDOFF 回传 Agent 不可用");
            }
            Map<String, Object> returnConfig = agentConfig(returnAgent);
            String returnTemplate = str(node.get("returnTemplate"));
            String returnMessage = returnTemplate == null || returnTemplate.isBlank()
                    ? "请基于以下交接结果继续处理并给出最终答复：\n" + answer
                    : applyStateTemplate(returnTemplate, run, stateOf(run), answer);
            Map<String, Object> returned;
            try {
                returned = chatService.debugChat(returnConfig, returnMessage, viewer, principal);
            } catch (Exception e) {
                throw new BusinessException(502, "HANDOFF 回传失败：" + rootMessage(e));
            }
            answer = str(returned.get("answer"));
            if (returned.get("usage") instanceof Map<?, ?> usage) {
                in += toLong(usage.get("inputTokens")) == null ? 0 : toLong(usage.get("inputTokens"));
                out += toLong(usage.get("outputTokens")) == null ? 0 : toLong(usage.get("outputTokens"));
            }
        }
        return new SimpleResult(answer, str(node.get("next")), in, out, false);
    }

    private NodeResult callModel(Map<String, Object> node, WorkflowRun run,
                                 List<Map<String, Object>> outputs, JwtPrincipal principal) {
        return callModel(node, run, outputs, principal, run.getInputText());
    }

    private NodeResult callModel(Map<String, Object> node, WorkflowRun run,
                                 List<Map<String, Object>> outputs, JwtPrincipal principal,
                                 String modelInput) {
        AgentRegistry agent = agentMapper.selectById(run.getAgentId());
        Map<String, Object> modelConfig = agent == null ? Map.of() : parseMap(agent.getModelConfig());
        String modelName = str(node.get("modelName"));
        if (modelName == null || modelName.isBlank()) modelName = str(modelConfig.get("modelName"));
        if (modelName == null || modelName.isBlank()) modelName = modelRouter.defaultModelName();

        StringBuilder system = new StringBuilder();
        if ("AGENT".equalsIgnoreCase(str(node.get("type"))) && node.get("agentId") != null) {
            AgentRegistry sub = agentMapper.selectById(toLong(node.get("agentId")));
            if (sub == null || "DELETED".equals(sub.getStatus())) {
                throw new BusinessException(400, "Agent 节点引用的 Agent 不可用");
            }
            if (sub.getSystemPrompt() != null) system.append(sub.getSystemPrompt()).append("\n\n");
            Map<String, Object> subModel = parseMap(sub.getModelConfig());
            if ((node.get("modelName") == null || str(node.get("modelName")).isBlank())
                    && subModel.get("modelName") != null) {
                modelName = str(subModel.get("modelName"));
            }
        }
        String prompt = str(node.get("prompt"));
        if (prompt != null && !prompt.isBlank()) system.append(prompt).append("\n\n");
        if (system.isEmpty()) system.append("你是工作流执行助手，请直接完成当前节点任务。");
        system.append("【用户输入】\n").append(modelInput == null ? "" : modelInput).append("\n");
        Map<String, Object> state = stateOf(run);
        if (!state.isEmpty()) {
            system.append("【共享状态】\n").append(json(state)).append("\n");
        }
        if (!outputs.isEmpty()) {
            system.append("【前序节点结果】\n");
            for (Map<String, Object> o : outputs) {
                system.append("- ").append(o.get("name")).append("：")
                        .append(truncate(str(o.get("output")), 1500)).append("\n");
            }
        }
        String fitted = contextWindow.fitPrompt(modelName, system.toString(),
                modelInput == null ? "" : modelInput);
        long start = System.currentTimeMillis();
        ChatResponse response = ChatClient.create(modelRouter.resolve(modelName)).prompt()
                .options(modelRouter.options(modelName, 0.3, java.util.List.of()))
                .system(fitted)
                .user(modelInput == null ? "" : modelInput)
                .call().chatResponse();
        String output = response.getResult() == null ? "" : response.getResult().getOutput().getText();
        long in = 0, out = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            in = response.getMetadata().getUsage().getPromptTokens();
            out = response.getMetadata().getUsage().getCompletionTokens();
        }
        recordUsage(run, modelName, in, out);
        log.info("工作流节点完成 run={} node={} model={} in={} out={} latency={}ms",
                run.getId(), node.get("id"), modelName, in, out, System.currentTimeMillis() - start);
        return new NodeResult(output == null ? "" : output, in, out);
    }

    private record SimpleResult(String output, String next, long inputTokens, long outputTokens,
                                boolean failed) {
    }

    private record LoopResult(String output, long inputTokens, long outputTokens,
                              Map<String, Object> state) {
    }

    private static final class LoopLimitExceededException extends RuntimeException {
        private final int requested;
        private final int limit;
        private final String preview;
        private final String partialOutput;
        private final List<String> remainingItems;
        private final long inputTokens;
        private final long outputTokens;

        LoopLimitExceededException(int requested, int limit, String preview, String partialOutput,
                                   List<String> remainingItems, long inputTokens, long outputTokens) {
            super("循环项数 " + requested + " 超过上限 " + limit + "，已执行 " + limit
                    + " 项并暂停，剩余 " + remainingItems.size() + " 项待人工确认");
            this.requested = requested;
            this.limit = limit;
            this.preview = preview;
            this.partialOutput = partialOutput;
            this.remainingItems = remainingItems;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }

    private record NodeResult(String output, long inputTokens, long outputTokens) {
    }

    private record BranchResult(String output, long inputTokens, long outputTokens,
                                Map<String, Object> state) {
    }

    private record ParallelResult(String output, long inputTokens, long outputTokens,
                                  Map<String, Object> state) {
    }

    /** 并行节点：分支用独立线程执行，结果文本汇总后进入 JOIN。 */
    private boolean isSimpleNode(String type) {
        return "LLM".equals(type) || "AGENT".equals(type) || "HANDOFF".equals(type)
                || "TOOL".equals(type) || "KNOWLEDGE".equals(type);
    }

    /** 节点级重试 + onError 策略（FAIL/CONTINUE/BRANCH）。 */
    private SimpleResult executeWithPolicy(String type, Map<String, Object> node, WorkflowRun run,
                                           List<Map<String, Object>> outputs, String lastOutput,
                                           JwtPrincipal principal, String modelInput) {
        int retries = Math.max(0, Math.min(toInt(node.get("retryTimes")), 3));
        int delay = Math.max(0, Math.min(toInt(node.get("retryDelayMs")), 5000));
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                SimpleResult result = executeSimpleNode(type, node, run, outputs, lastOutput, principal, modelInput);
                return new SimpleResult(result.output(), str(node.get("next")),
                        result.inputTokens(), result.outputTokens(), false);
            } catch (Exception e) {
                last = e;
                if (attempt < retries) {
                    log.warn("工作流节点失败，重试 {}/{} node={}：{}",
                            attempt + 1, retries, node.get("id"), rootMessage(e));
                    if (delay > 0) {
                        try { Thread.sleep(delay); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
        String policy = str(node.getOrDefault("onError", "FAIL")).toUpperCase();
        String message = "【节点失败】" + rootMessage(last);
        if ("CONTINUE".equals(policy)) {
            return new SimpleResult(message, str(node.get("next")), 0, 0, true);
        }
        if ("BRANCH".equals(policy)) {
            return new SimpleResult(message, str(node.get("errorNext")), 0, 0, true);
        }
        throw new BusinessException(500, "工作流节点失败：" + rootMessage(last));
    }

    private SimpleResult executeSimpleNode(String type, Map<String, Object> node, WorkflowRun run,
                                           List<Map<String, Object>> outputs, String lastOutput,
                                           JwtPrincipal principal, String modelInput) {
        if ("LLM".equals(type) || "AGENT".equals(type)) {
            NodeResult result = callModel(node, run, outputs, principal, modelInput);
            return new SimpleResult(result.output(), str(node.get("next")),
                    result.inputTokens(), result.outputTokens(), false);
        }
        if ("HANDOFF".equals(type)) {
            return handoff(node, run, principal, modelInput);
        }
        if ("TOOL".equals(type)) {
            return new SimpleResult(callTool(node, run, lastOutput), str(node.get("next")), 0, 0, false);
        }
        if ("KNOWLEDGE".equals(type)) {
            return new SimpleResult(retrieveKnowledge(node, run, lastOutput, principal),
                    str(node.get("next")), 0, 0, false);
        }
        throw new BusinessException(400, "不支持的简单节点类型：" + type);
    }

    private LoopResult executeLoop(Map<String, Object> node, WorkflowRun run,
                                   Map<String, Map<String, Object>> nodes,
                                   List<Map<String, Object>> outputs, String lastOutput,
                                   JwtPrincipal principal, Map<String, Object> baseState) {
        String body = str(node.get("bodyNode"));
        if (body == null || body.isBlank()) throw new BusinessException(400, "LOOP 节点未配置 bodyNode");
        String loopEnd = str(node.get("loopEnd"));
        int max = Math.max(1, Math.min(toInt(node.getOrDefault("maxIterations", 10)), 50));
        List<String> items = loopItems(node, run, lastOutput);
        if (items.isEmpty()) return new LoopResult(lastOutput == null ? "" : lastOutput, 0, 0, baseState);
        int count = Math.min(items.size(), max);
        StringBuilder merged = new StringBuilder("【循环结果】");
        Map<String, Object> loopState = new LinkedHashMap<>(baseState);
        long in = 0, out = 0;
        for (int i = 0; i < count; i++) {
            BranchResult br = executeBranch(run, nodes, body, loopEnd, outputs, principal, items.get(i),
                    loopState);
            merged.append("\n[").append(i + 1).append("] ").append(br.output());
            in += br.inputTokens();
            out += br.outputTokens();
            loopState.putAll(br.state());
        }
        if (items.size() > max) {
            String preview = items.subList(max, items.size()).stream().limit(5).map(s -> truncate(s, 80))
                    .reduce((a, b) -> a + " | " + b).orElse("");
            throw new LoopLimitExceededException(items.size(), max, preview, merged.toString(),
                    new ArrayList<>(items.subList(max, items.size())), in, out);
        }
        return new LoopResult(merged.toString(), in, out, loopState);
    }

    private List<String> loopItems(Map<String, Object> node, WorkflowRun run, String lastOutput) {
        Object raw = node.get("items");
        List<String> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) items.add(String.valueOf(o));
        } else {
            String text = raw == null || String.valueOf(raw).isBlank()
                    ? (lastOutput == null ? run.getInputText() : lastOutput) : String.valueOf(raw);
            text = text.replace("{{input}}", run.getInputText())
                    .replace("{{last}}", lastOutput == null ? "" : lastOutput);
            String[] parts = text.contains("\n") ? text.split("\r?\n") : text.split("[,，]");
            for (String part : parts) {
                if (part != null && !part.isBlank()) items.add(part.trim());
            }
        }
        return items;
    }

    /** 并行网关前置审批：收集分支内 APPROVAL，统一审批后放行；返回 false 表示已暂停。 */
    private boolean ensureParallelApprovals(Map<String, Object> node, String parallelNodeId,
                                            WorkflowRun run, Map<String, Map<String, Object>> nodes,
                                            WorkflowNodeRun nr, JwtPrincipal principal) {
        String join = str(node.get("joinNode"));
        Set<String> approvals = new java.util.LinkedHashSet<>();
        if (node.get("branches") instanceof List<?> branches) {
            for (Object b : branches) {
                collectApprovals(str(b), join, nodes, new java.util.HashSet<>(), approvals);
            }
        }
        if (approvals.isEmpty()) return true;
        Set<String> approved = approvedBranchApprovals(run);
        if (approved.containsAll(approvals)) return true;

        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(run.getId());
        approval.setAgentId(run.getAgentId());
        approval.setTenantId(run.getTenantId());
        approval.setNodeId(parallelNodeId);
        approval.setTitle("并行分支审批（" + approvals.size() + " 项）");
        String requiredRole = str(node.get("requiredRole"));
        approval.setRequiredRole(requiredRole);
        int timeoutMinutes = toInt(node.get("timeoutMinutes"));
        if (timeoutMinutes > 0) approval.setExpireAt(LocalDateTime.now().plusMinutes(timeoutMinutes));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branchApprovalGate", true);
        payload.put("parallelNodeId", parallelNodeId);
        payload.put("approvalNodeIds", new ArrayList<>(approvals));
        payload.put("message", "并行分支包含人工审批节点，请统一审批后继续");
        approval.setPayload(json(payload));
        approval.setStatus("PENDING");
        approval.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(approval);
        List<Long> recipients = requiredRole == null || requiredRole.isBlank()
                ? userMapper.findOwnerAdminUserIds(run.getTenantId())
                : userMapper.findUserIdsByRole(run.getTenantId(), requiredRole);
        if (recipients.isEmpty()) recipients = userMapper.findOwnerAdminUserIds(run.getTenantId());
        notificationService.notify(run.getTenantId(), recipients, "workflow_approval",
                "并行分支待审批：" + approval.getTitle(), "工作流 " + run.getId() + " 需要审批",
                "/approvals");
        nr.setStatus("WAITING");
        nr.setOutputText("并行分支审批中");
        nr.setEndedAt(LocalDateTime.now());
        nodeRunMapper.updateById(nr);
        run.setStatus("WAITING_APPROVAL");
        run.setCurrentNode(parallelNodeId);
        run.setOutputText("并行分支审批中");
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        auditService.record(principal.userId(), "workflow.parallel_approval", "workflow_run",
                String.valueOf(run.getId()), "WAITING_APPROVAL",
                Map.of("parallelNodeId", parallelNodeId, "approvals", approvals.size(),
                        "approvalId", approval.getId()));
        return false;
    }

    private void collectApprovals(String nodeId, String joinId,
                                  Map<String, Map<String, Object>> nodes,
                                  Set<String> visited, Set<String> approvals) {
        String current = nodeId;
        while (current != null && !current.isBlank() && !current.equals(joinId) && visited.add(current)) {
            Map<String, Object> n = nodes.get(current);
            if (n == null) return;
            String type = str(n.getOrDefault("type", "LLM")).toUpperCase();
            if ("APPROVAL".equals(type)) approvals.add(current);
            if ("PARALLEL".equals(type)) {
                if (n.get("branches") instanceof List<?> bs) {
                    for (Object b : bs) collectApprovals(str(b), str(n.get("joinNode")), nodes, visited, approvals);
                }
                current = str(n.get("joinNode"));
                continue;
            }
            if ("CONDITION".equals(type)) {
                collectApprovals(str(n.get("trueNext")), joinId, nodes, visited, approvals);
                collectApprovals(str(n.get("falseNext")), joinId, nodes, visited, approvals);
                return;
            }
            if ("END".equals(type)) return;
            current = str(n.get("next"));
        }
    }

    private Set<String> approvedBranchApprovals(WorkflowRun run) {
        if (run == null || run.getContextJson() == null) return Set.of();
        Map<String, Object> ctx = parseMap(run.getContextJson());
        if (!(ctx.get("branchApprovals") instanceof Map<?, ?> m)) return Set.of();
        Set<String> result = new java.util.HashSet<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) result.add(String.valueOf(e.getKey()));
        }
        return result;
    }

    private ParallelResult executeParallel(Map<String, Object> node, WorkflowRun run,
                                            Map<String, Map<String, Object>> nodes,
                                            List<Map<String, Object>> outputs, JwtPrincipal principal,
                                            Map<String, Object> baseState) {
        Object raw = node.get("branches");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(400, "PARALLEL 节点没有分支");
        }
        String joinId = str(node.get("joinNode"));
        String strategy = str(node.getOrDefault("strategy", "ALL_OR_NOTHING"));
        if (strategy == null || strategy.isBlank()) strategy = "ALL_OR_NOTHING";
        List<CompletableFuture<BranchResult>> futures = new ArrayList<>();
        for (Object o : list) {
            String start = str(o);
            futures.add(CompletableFuture.supplyAsync(() -> TenantContext.runWith(run.getTenantId(),
                    () -> executeBranch(run, nodes, start, joinId, outputs, principal, null,
                            new LinkedHashMap<>(baseState))), llmExecutor));
        }
        StringBuilder merged = new StringBuilder("【并行分支结果】\n");
        long in = 0, out = 0;
        int failed = 0;
        Map<String, Object> mergedState = new LinkedHashMap<>(baseState);
        for (int i = 0; i < futures.size(); i++) {
            merged.append("\n[分支 ").append(i + 1).append("]\n");
            try {
                BranchResult br = futures.get(i).join();
                merged.append(br.output() == null ? "" : br.output()).append("\n");
                in += br.inputTokens();
                out += br.outputTokens();
                mergedState.putAll(br.state());
            } catch (Exception e) {
                failed++;
                if ("ALL_OR_NOTHING".equalsIgnoreCase(strategy)) {
                    throw new BusinessException(500, "并行分支执行失败：" + rootMessage(e));
                }
                merged.append("[分支失败] ").append(rootMessage(e)).append("\n");
            }
        }
        if (failed > 0 && failed == futures.size()) {
            throw new BusinessException(500, "并行分支全部失败");
        }
        return new ParallelResult(merged.toString(), in, out, mergedState);
    }

    private BranchResult executeBranch(WorkflowRun run, Map<String, Map<String, Object>> nodes,
                                       String start, String joinId, List<Map<String, Object>> baseOutputs,
                                       JwtPrincipal principal, String initialOutput,
                                       Map<String, Object> baseState) {
        String nodeId = start;
        String lastOutput = initialOutput == null ? run.getOutputText() : initialOutput;
        List<Map<String, Object>> outputs = new ArrayList<>(baseOutputs);
        Map<String, Object> state = new LinkedHashMap<>(baseState);
        long in = 0, out = 0;
        int steps = 0;
        while (steps++ < 30) {
            if (nodeId == null || nodeId.isBlank() || nodeId.equals(joinId)) break;
            Map<String, Object> node = nodes.get(nodeId);
            if (node == null) throw new BusinessException(500, "分支节点不存在：" + nodeId);
            String type = str(node.getOrDefault("type", "LLM")).toUpperCase();
            if ("END".equals(type) || "JOIN".equals(type)) break;
            if ("START".equals(type)) {
                lastOutput = run.getInputText();
                nodeId = str(node.get("next"));
                continue;
            }
            if ("APPROVAL".equals(type)) {
                if (!approvedBranchApprovals(run).contains(nodeId)) {
                    throw new BusinessException(400, "并行分支审批未通过：" + nodeId);
                }
                String name = str(node.getOrDefault("name", nodeId));
                WorkflowNodeRun gateNr = new WorkflowNodeRun();
                gateNr.setRunId(run.getId());
                gateNr.setTenantId(run.getTenantId());
                gateNr.setNodeId(nodeId);
                gateNr.setNodeType(type);
                gateNr.setName(name);
                gateNr.setStatus("SUCCESS");
                gateNr.setInputText(truncate(lastOutput, 8000));
                gateNr.setOutputText("APPROVED（并行分支网关）");
                gateNr.setStartedAt(LocalDateTime.now());
                gateNr.setEndedAt(LocalDateTime.now());
                nodeRunMapper.insert(gateNr);
                
                nodeId = str(node.get("next"));
                continue;
            }
            String name = str(node.getOrDefault("name", nodeId));
            WorkflowNodeRun nr = new WorkflowNodeRun();
            nr.setRunId(run.getId());
            nr.setTenantId(run.getTenantId());
            nr.setNodeId(nodeId);
            nr.setNodeType(type);
            nr.setName(name);
            nr.setStatus("RUNNING");
            nr.setInputText(truncate(lastOutput, 8000));
            nr.setStartedAt(LocalDateTime.now());
            nodeRunMapper.insert(nr);

            String output;
            String next;
            boolean nodeFailed = false;
            SimpleResult simple = isSimpleNode(type)
                    ? executeWithPolicy(type, node, run, outputs, lastOutput, principal, lastOutput) : null;
            if (simple != null) {
                output = simple.output();
                next = simple.next();
                nodeFailed = simple.failed();
                in += simple.inputTokens();
                out += simple.outputTokens();
            } else if ("LOOP".equals(type)) {
                try {
                    LoopResult loop = executeLoop(node, run, nodes, outputs, lastOutput, principal, state);
                    output = loop.output();
                    next = str(node.get("next"));
                    in += loop.inputTokens();
                    out += loop.outputTokens();
                    state.putAll(loop.state());
                } catch (LoopLimitExceededException e) {
                    throw new BusinessException(400, "并行分支内 LOOP 超限暂不支持人工续跑：" + e.getMessage());
                }
            } else if ("PARALLEL".equals(type)) {
                ParallelResult nested = executeParallel(node, run, nodes, outputs, principal, state);
                output = nested.output();
                next = str(node.get("joinNode"));
                in += nested.inputTokens();
                out += nested.outputTokens();
                state.putAll(nested.state());
            } else if ("STATE".equals(type)) {
                String key = str(node.get("stateKey"));
                if (key == null || key.isBlank()) throw new BusinessException(400, "STATE 节点必须配置 stateKey");
                String template = str(node.get("valueTemplate"));
                String value = template == null || template.isBlank() ? lastOutput
                        : applyStateTemplate(template, run, state, lastOutput);
                state.put(key, value == null ? "" : value);
                output = value == null ? "" : value;
                next = str(node.get("next"));
            } else if ("CONDITION".equals(type)) {
                boolean matched = evaluate(node, lastOutput == null ? run.getInputText() : lastOutput);
                output = matched ? "true" : "false";
                next = matched ? str(node.get("trueNext")) : str(node.get("falseNext"));
            } else {
                throw new BusinessException(400, "分支内不支持的节点类型：" + type);
            }
            nr.setStatus(nodeFailed ? "FAILED" : "SUCCESS");
            nr.setOutputText(truncate(output, 20000));
            nr.setEndedAt(LocalDateTime.now());
            nodeRunMapper.updateById(nr);
            outputs.add(Map.of("nodeId", nodeId, "name", name,
                    "output", truncate(output, 1500)));
            lastOutput = output;
            nodeId = next;
            if (nodeId == null || nodeId.isBlank()) break;
        }
        return new BranchResult(lastOutput, in, out, state);
    }

    private String callTool(Map<String, Object> node, WorkflowRun run, String lastOutput) {
        Long toolId = toLong(node.get("toolId"));
        if (toolId == null) throw new BusinessException(400, "TOOL 节点未选择工具");
        String args = str(node.get("argsTemplate"));
        if (args == null || args.isBlank()) args = "{}";
        args = args.replace("{{input}}", run.getInputText())
                .replace("{{last}}", lastOutput == null ? "" : lastOutput);
        Map<String, Object> result = toolRuntime.executeToolForWorkflow(run.getAgentId(), toolId, args);
        return str(result.get("output"));
    }

    private String retrieveKnowledge(Map<String, Object> node, WorkflowRun run,
                                     String lastOutput, JwtPrincipal principal) {
        Long kbId = toLong(node.get("knowledgeBaseId"));
        if (kbId == null) throw new BusinessException(400, "KNOWLEDGE 节点未选择知识库");
        var viewer = KnowledgePermissionService.Viewer.of(principal.userId(), principal.roles());
        if (permissionService.findVisible(kbId, viewer) == null) {
            throw new BusinessException(403, "无权访问知识库 #" + kbId);
        }
        String query = str(node.get("queryTemplate"));
        if (query == null || query.isBlank()) query = run.getInputText();
        query = query.replace("{{input}}", run.getInputText())
                .replace("{{last}}", lastOutput == null ? "" : lastOutput);
        int topK = toInt(node.get("topK"));
        if (topK <= 0) topK = 5;
        double threshold = toDouble(node.get("scoreThreshold"), 0.5);
        String mode = str(node.get("mode"));
        Map<String, Object> cfg = mode == null || mode.isBlank() ? Map.of() : Map.of("mode", mode);
        var result = hybridRetrieval.search(run.getTenantId(), query, kbId, topK, threshold, cfg,
                viewer.userId(), permissionService.tenantWide(viewer));
        if (result.hits().isEmpty()) return "（知识库未检索到相关内容）";
        StringBuilder sb = new StringBuilder("【知识库检索结果】\n");
        int index = 1;
        for (Map<String, Object> hit : result.hits()) {
            sb.append("\n--- [" + index++ + "] ---\n").append(str(hit.get("content")));
        }
        return sb.toString();
    }

    private void recordUsage(WorkflowRun run, String modelName, long in, long out) {
        try {
            BillingUsage u = new BillingUsage();
            u.setTenantId(run.getTenantId());
            u.setAgentId(run.getAgentId());
            u.setSource("workflow");
            u.setModelName(modelName);
            u.setInputTokens(in);
            u.setOutputTokens(out);
            u.setCost(pricingService.cost(run.getTenantId(), modelName, in, out));
            u.setStatDate(LocalDate.now());
            u.setCreatedAt(LocalDateTime.now());
            billingMapper.insert(u);
        } catch (Exception e) {
            log.debug("工作流用量记录失败：{}", e.getMessage());
        }
    }

    // ---------- 查询 ----------

    public Map<String, Object> runDetail(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException(404, "运行记录不存在");
        return runView(run);
    }

    public Map<String, Object> runs(Long agentId, long page, long pageSize) {
        requireAgent(agentId);
        Page<WorkflowRun> p = runMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 50)),
                new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getAgentId, agentId)
                        .orderByDesc(WorkflowRun::getId));
        List<Map<String, Object>> list = p.getRecords().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("status", r.getStatus());
            m.put("input", r.getInputText());
            m.put("output", r.getOutputText());
            m.put("currentNode", r.getCurrentNode());
            m.put("inputTokens", r.getInputTokens());
            m.put("outputTokens", r.getOutputTokens());
            m.put("createdAt", r.getCreatedAt());
            m.put("updatedAt", r.getUpdatedAt());
            return m;
        }).toList();
        return Map.of("list", list, "total", p.getTotal(), "page", page, "pageSize", pageSize);
    }

    public Map<String, Object> approvals(String status, long page, long pageSize) {
        String st = status == null || status.isBlank() ? "PENDING" : status.toUpperCase();
        Page<WorkflowApproval> p = approvalMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 50)),
                new LambdaQueryWrapper<WorkflowApproval>()
                        .eq(!"ALL".equals(st), WorkflowApproval::getStatus, st)
                        .orderByDesc(WorkflowApproval::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (WorkflowApproval a : p.getRecords()) {
            WorkflowRun run = runMapper.selectById(a.getRunId());
            AgentRegistry agent = agentMapper.selectById(a.getAgentId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("runId", a.getRunId());
            m.put("agentId", a.getAgentId());
            m.put("agentName", agent == null ? "Agent #" + a.getAgentId() : agent.getName());
            m.put("nodeId", a.getNodeId());
            m.put("title", a.getTitle());
            m.put("requiredRole", a.getRequiredRole());
            m.put("expireAt", a.getExpireAt());
            m.put("payload", a.getPayload());
            m.put("status", a.getStatus());
            m.put("comment", a.getComment());
            m.put("decidedAt", a.getDecidedAt());
            m.put("createdAt", a.getCreatedAt());
            m.put("runStatus", run == null ? null : run.getStatus());
            m.put("runInput", run == null ? null : run.getInputText());
            list.add(m);
        }
        return Map.of("list", list, "total", p.getTotal(), "page", page, "pageSize", pageSize);
    }

    private String contextJson(List<Map<String, Object>> outputs, Map<String, Object> state) {
        return json(Map.of("outputs", outputs, "state", state));
    }

    private Map<String, Object> stateOf(WorkflowRun run) {
        if (run == null || run.getContextJson() == null) return Map.of();
        Map<String, Object> ctx = parseMap(run.getContextJson());
        return ctx.get("state") instanceof Map<?, ?> sm ? castMap(sm) : Map.of();
    }

    private String applyStateTemplate(String template, WorkflowRun run, Map<String, Object> state,
                                      String lastOutput) {
        String out = template
                .replace("{{input}}", run.getInputText() == null ? "" : run.getInputText())
                .replace("{{last}}", lastOutput == null ? "" : lastOutput);
        for (Map.Entry<String, Object> e : state.entrySet()) {
            out = out.replace("{{state." + e.getKey() + "}}",
                    e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    private Map<String, Object> runView(WorkflowRun run) {
        if (run == null) throw new BusinessException(404, "运行记录不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("workflowId", run.getWorkflowId());
        m.put("agentId", run.getAgentId());
        m.put("status", run.getStatus());
        m.put("input", run.getInputText());
        m.put("output", run.getOutputText());
        m.put("currentNode", run.getCurrentNode());
        m.put("error", run.getErrorMessage());
        m.put("state", stateOf(run));
        m.put("inputTokens", run.getInputTokens());
        m.put("outputTokens", run.getOutputTokens());
        m.put("createdAt", run.getCreatedAt());
        m.put("updatedAt", run.getUpdatedAt());
        List<WorkflowNodeRun> nodes = nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, run.getId()).orderByAsc(WorkflowNodeRun::getId));
        m.put("nodeRuns", nodes);
        List<WorkflowApproval> approvals = approvalMapper.selectList(
                new LambdaQueryWrapper<WorkflowApproval>()
                        .eq(WorkflowApproval::getRunId, run.getId()).orderByAsc(WorkflowApproval::getId));
        m.put("approvals", approvals);
        return m;
    }

    // ---------- 校验/辅助 ----------

    private AgentRegistry requireAgent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) {
            throw new BusinessException(404, "Agent 不存在");
        }
        return agent;
    }

    /**
     * 独立 Graph 边：edges=[{from,to,label}]。保存/运行前归一化为节点的 next/trueNext/falseNext，
     * 兼容原有执行器；PARALLEL 分支仍由 branches 配置表达。
     */
    @SuppressWarnings("unchecked")
    private void normalizeGraph(Map<String, Object> definition) {
        Object rawEdges = definition.get("edges");
        if (!(rawEdges instanceof List<?> edges)) return;
        if (edges.isEmpty()) {
            definition.remove("edges");
            return;
        }
        if (edges.size() > 100) throw new BusinessException(400, "Graph 边不能超过 100 条");
        Map<String, Map<String, Object>> nodes = nodeMap(definition);
        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        for (Object o : edges) {
            if (!(o instanceof Map<?, ?> em)) throw new BusinessException(400, "Graph 边格式错误");
            Map<String, Object> edge = castMap(em);
            String from = str(edge.get("from"));
            String to = str(edge.get("to"));
            if (from == null || from.isBlank() || !nodes.containsKey(from)) {
                throw new BusinessException(400, "Graph 边起点不存在：" + from);
            }
            if (to == null || to.isBlank() || !nodes.containsKey(to)) {
                throw new BusinessException(400, "Graph 边终点不存在：" + to);
            }
            String fromType = str(nodes.get(from).get("type"));
            if ("END".equalsIgnoreCase(fromType)) throw new BusinessException(400, "END 节点不能有出边");
            outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : outgoing.entrySet()) {
            Map<String, Object> node = nodes.get(entry.getKey());
            String type = str(node.get("type"));
            type = type == null ? "" : type.toUpperCase();
            List<Map<String, Object>> outs = entry.getValue();
            if ("CONDITION".equals(type)) {
                for (Map<String, Object> edge : outs) {
                    String label = str(edge.get("label"));
                    if ("true".equalsIgnoreCase(label)) node.put("trueNext", str(edge.get("to")));
                    else if ("false".equalsIgnoreCase(label)) node.put("falseNext", str(edge.get("to")));
                }
                if (str(node.get("trueNext")) == null || str(node.get("falseNext")) == null) {
                    throw new BusinessException(400, "CONDITION 的 Graph 边必须分别标记 true/false");
                }
            } else if (!"PARALLEL".equals(type)) {
                if (outs.size() > 1) {
                    throw new BusinessException(400, "节点 " + entry.getKey() + " 只能有一条出边");
                }
                node.put("next", str(outs.get(0).get("to")));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> definition) {
        Object rawNodes = definition.get("nodes");
        if (!(rawNodes instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(400, "工作流至少包含 START/END 节点");
        }
        if (list.size() > 30) throw new BusinessException(400, "工作流节点不能超过 30 个");
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        boolean hasStart = false, hasEnd = false;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new BusinessException(400, "节点格式错误");
            Map<String, Object> node = castMap(m);
            String id = str(node.get("id"));
            String type = str(node.get("type"));
            if (id == null || id.isBlank()) throw new BusinessException(400, "节点 id 不能为空");
            if (nodes.containsKey(id)) throw new BusinessException(400, "节点 id 重复：" + id);
            String t = type == null ? "" : type.toUpperCase();
            if (!List.of("START", "LLM", "AGENT", "TOOL", "KNOWLEDGE", "CONDITION", "APPROVAL",
                    "PARALLEL", "LOOP", "JOIN", "HANDOFF", "STATE", "END").contains(t)) {
                throw new BusinessException(400, "不支持的节点类型：" + type);
            }
            if ("START".equals(t)) hasStart = true;
            if ("END".equals(t)) hasEnd = true;
            nodes.put(id, node);
        }
        if (!hasStart || !hasEnd) throw new BusinessException(400, "必须包含 START 和 END 节点");
        for (Map<String, Object> node : nodes.values()) {
            String type = str(node.get("type"));
            type = type == null ? "" : type.toUpperCase();
            if ("END".equals(type)) continue;
            if ("CONDITION".equals(type)) {
                checkTarget(nodes, str(node.get("trueNext")), "条件节点 trueNext");
                checkTarget(nodes, str(node.get("falseNext")), "条件节点 falseNext");
            } else if (!"PARALLEL".equals(type)) {
                checkTarget(nodes, str(node.get("next")), "节点 next");
            }
            if (("AGENT".equals(type) || "HANDOFF".equals(type)) && node.get("agentId") == null) {
                throw new BusinessException(400, type + " 节点必须选择 Agent");
            }
            if ("STATE".equals(type) && (node.get("stateKey") == null || str(node.get("stateKey")).isBlank())) {
                throw new BusinessException(400, "STATE 节点必须配置 stateKey");
            }
            if ("TOOL".equals(type) && node.get("toolId") == null) {
                throw new BusinessException(400, "TOOL 节点必须选择工具");
            }
            if ("LOOP".equals(type)) {
                int maxIterations = toInt(node.getOrDefault("maxIterations", 10));
                if (maxIterations < 1 || maxIterations > 50) {
                    throw new BusinessException(400, "LOOP maxIterations 必须在 1-50 之间");
                }
                String body = str(node.get("bodyNode"));
                if (body == null || body.isBlank()) {
                    throw new BusinessException(400, "LOOP 节点必须配置 bodyNode");
                }
                if (!nodes.containsKey(body)) {
                    throw new BusinessException(400, "LOOP bodyNode 不存在：" + body);
                }
                String loopEnd = str(node.get("loopEnd"));
                if (loopEnd != null && !loopEnd.isBlank() && !nodes.containsKey(loopEnd)) {
                    throw new BusinessException(400, "LOOP loopEnd 不存在：" + loopEnd);
                }
            }
            if ("KNOWLEDGE".equals(type) && node.get("knowledgeBaseId") == null) {
                throw new BusinessException(400, "KNOWLEDGE 节点必须选择知识库");
            }
            if ("PARALLEL".equals(type)) {
                Object branches = node.get("branches");
                if (!(branches instanceof List<?> branchList) || branchList.isEmpty()) {
                    throw new BusinessException(400, "PARALLEL 节点至少需要一个分支起点");
                }
                for (Object b : branchList) {
                    checkTarget(nodes, str(b), "并行分支起点");
                }
                String join = str(node.get("joinNode"));
                checkTarget(nodes, join, "并行汇合节点");
                if (!"JOIN".equalsIgnoreCase(str(nodes.get(join).get("type")))) {
                    throw new BusinessException(400, "PARALLEL.joinNode 必须是 JOIN 节点");
                }
            }
            if ("JOIN".equals(type) && (node.get("next") == null || str(node.get("next")).isBlank())) {
                throw new BusinessException(400, "JOIN 节点必须配置 next");
            }
        }
    }

    private void checkTarget(Map<String, Map<String, Object>> nodes, String id, String label) {
        if (id == null || id.isBlank() || !nodes.containsKey(id)) {
            throw new BusinessException(400, label + " 指向的节点不存在：" + id);
        }
    }

    private Map<String, Object> findNode(Map<String, Object> definition, String nodeId) {
        Object raw = definition.get("nodes");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && nodeId.equals(str(m.get("id")))) return castMap(m);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> nodeMap(Map<String, Object> definition) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Object raw = definition.get("nodes");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> node = castMap(m);
                    nodes.put(str(node.get("id")), node);
                }
            }
        }
        return nodes;
    }

    private boolean evaluate(Map<String, Object> node, String value) {
        String type = str(node.getOrDefault("conditionType", "contains"));
        String expected = str(node.get("conditionValue"));
        if (value == null) value = "";
        if (expected == null) expected = "";
        return switch (type) {
            case "equals" -> value.trim().equals(expected);
            case "notContains" -> !value.contains(expected);
            case "lengthGt" -> value.length() > toInt(expected);
            default -> value.contains(expected);
        };
    }

    /** 审批超时扫描（调度器/手动触发）。 */
    public int scanExpiredApprovals() {
        List<WorkflowApproval> expired = approvalMapper.findExpired(LocalDateTime.now(), 100);
        int count = 0;
        for (WorkflowApproval a : expired) {
            try {
                boolean ok = TenantContext.runWith(a.getTenantId(), () -> expireOne(a));
                if (ok) count++;
            } catch (Exception e) {
                log.warn("审批超时处理失败 approval={}：{}", a.getId(), e.getMessage());
            }
        }
        return count;
    }

    private boolean expireOne(WorkflowApproval approval) {
        int updated = approvalMapper.update(null, new LambdaUpdateWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getId, approval.getId())
                .eq(WorkflowApproval::getStatus, "PENDING")
                .set(WorkflowApproval::getStatus, "EXPIRED")
                .set(WorkflowApproval::getDecidedAt, LocalDateTime.now())
                .set(WorkflowApproval::getComment, "审批超时自动关闭"));
        if (updated == 0) return false;
        WorkflowRun run = runMapper.selectById(approval.getRunId());
        if (run != null && "WAITING_APPROVAL".equals(run.getStatus())) {
            run.setStatus("FAILED");
            run.setErrorMessage("审批超时未处理：" + approval.getTitle());
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            WorkflowNodeRun nodeRun = nodeRunMapper.selectOne(new LambdaQueryWrapper<WorkflowNodeRun>()
                    .eq(WorkflowNodeRun::getRunId, run.getId())
                    .eq(WorkflowNodeRun::getNodeId, approval.getNodeId())
                    .orderByDesc(WorkflowNodeRun::getId).last("LIMIT 1"));
            if (nodeRun != null) {
                nodeRun.setStatus("EXPIRED");
                nodeRun.setEndedAt(LocalDateTime.now());
                nodeRun.setOutputText("TIMEOUT");
                nodeRunMapper.updateById(nodeRun);
            }
            notificationService.notify(run.getTenantId(),
                    run.getUserId() == null ? List.of() : List.of(run.getUserId()),
                    "workflow_result", "审批超时：" + approval.getTitle(),
                    "工作流 " + run.getId() + " 因审批超时已终止",
                    "/agent/edit/" + run.getAgentId());
        }
        auditService.record(approval.getDecidedBy() == null ? 0L : approval.getDecidedBy(),
                "workflow.timeout", "workflow_approval", String.valueOf(approval.getId()),
                "SUCCESS", Map.of("runId", approval.getRunId()));
        return true;
    }

    private void fail(WorkflowRun run, String error) {
        run.setStatus("FAILED");
        run.setErrorMessage(truncate(error, 1000));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private Map<String, Object> defaultDefinition() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "START", "开始", "n1", null, null));
        nodes.add(node("n1", "LLM", "生成回答", "end", "根据用户输入给出简洁、准确的回答。", null));
        nodes.add(node("end", "END", "结束", null, null, null));
        return Map.of("nodes", nodes);
    }

    private Map<String, Object> node(String id, String type, String name, String next,
                                     String prompt, String agentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("name", name);
        if (next != null) m.put("next", next);
        if (prompt != null) m.put("prompt", prompt);
        if (agentId != null) m.put("agentId", agentId);
        return m;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException(400, "工作流定义不是合法 JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private long nz(Long v) {
        return v == null ? 0 : v;
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    private double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
