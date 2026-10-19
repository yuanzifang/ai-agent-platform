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
import java.util.UUID;

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
    private final AgentRegistryMapper agentMapper;
    private final ChatModelRouter modelRouter;
    private final com.platform.agent.service.ContextWindowService contextWindow;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;
    private final AuditService auditService;
    private final com.platform.capability.runtime.ToolRuntimeService toolRuntime;
    private final HybridRetrievalService hybridRetrieval;
    private final KnowledgePermissionService permissionService;
    private final ObjectMapper objectMapper;

    private static final int MAX_STEPS = 50;

    // ---------- 配置 ----------

    public Map<String, Object> get(Long agentId) {
        AgentRegistry agent = requireAgent(agentId);
        AgentWorkflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<AgentWorkflow>()
                .eq(AgentWorkflow::getAgentId, agentId).last("LIMIT 1"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", wf != null);
        m.put("agentId", agentId);
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

    public void delete(Long agentId, JwtPrincipal principal) {
        requireAgent(agentId);
        workflowMapper.delete(new LambdaQueryWrapper<AgentWorkflow>().eq(AgentWorkflow::getAgentId, agentId));
        auditService.record(principal.userId(), "workflow.delete", "agent_workflow",
                String.valueOf(agentId), "SUCCESS", Map.of());
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
        validate(parse(wf.getDefinition()));

        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(wf.getId());
        run.setAgentId(agentId);
        run.setTenantId(wf.getTenantId());
        run.setDefinition(wf.getDefinition());
        run.setInputText(input.trim());
        run.setOutputText(input.trim());
        run.setContextJson(json(Map.of("outputs", List.of())));
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
        WorkflowRun run = runMapper.selectById(approval.getRunId());
        if (run == null) throw new BusinessException(404, "运行记录不存在");
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
            String next = str(node.get("next"));
            run.setStatus("RUNNING");
            run.setCurrentNode(next);
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            auditService.record(principal.userId(), "workflow.approve", "workflow_approval",
                    String.valueOf(approvalId), "SUCCESS", Map.of("runId", run.getId()));
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
            if (run.getContextJson() != null) {
                Map<String, Object> ctx = parseMap(run.getContextJson());
                if (ctx.get("outputs") instanceof List<?> l) {
                    for (Object o : l) if (o instanceof Map<?, ?> m) outputs.add(castMap(m));
                }
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
                    run.setContextJson(json(Map.of("outputs", outputs)));
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
                if ("START".equals(type)) {
                    output = run.getInputText();
                    next = str(node.get("next"));
                } else if ("LLM".equals(type) || "AGENT".equals(type)) {
                    NodeResult result = callModel(node, run, outputs, principal);
                    output = result.output();
                    run.setInputTokens(nz(run.getInputTokens()) + result.inputTokens());
                    run.setOutputTokens(nz(run.getOutputTokens()) + result.outputTokens());
                    next = str(node.get("next"));
                } else if ("TOOL".equals(type)) {
                    output = callTool(node, run, lastOutput);
                    next = str(node.get("next"));
                } else if ("KNOWLEDGE".equals(type)) {
                    output = retrieveKnowledge(node, run, lastOutput, principal);
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
                    approval.setTitle(str(node.getOrDefault("title", name)));
                    approval.setRequiredRole(str(node.get("requiredRole")));
                    approval.setPayload(truncate(output, 20000));
                    approval.setStatus("PENDING");
                    approval.setCreatedAt(LocalDateTime.now());
                    approvalMapper.insert(approval);
                    nr.setStatus("WAITING");
                    nr.setOutputText(truncate(output, 20000));
                    nr.setEndedAt(LocalDateTime.now());
                    nodeRunMapper.updateById(nr);
                    run.setStatus("WAITING_APPROVAL");
                    run.setCurrentNode(nodeId);
                    run.setOutputText(output);
                    run.setUpdatedAt(LocalDateTime.now());
                    run.setContextJson(json(Map.of("outputs", outputs)));
                    runMapper.updateById(run);
                    return;
                } else {
                    fail(run, "不支持的节点类型：" + type);
                    return;
                }

                nr.setStatus("SUCCESS");
                nr.setOutputText(truncate(output, 20000));
                nr.setEndedAt(LocalDateTime.now());
                nodeRunMapper.updateById(nr);
                outputs.add(Map.of("nodeId", nodeId, "name", name, "output", truncate(output, 2000)));
                lastOutput = output;
                run.setOutputText(output);
                run.setCurrentNode(next);
                run.setUpdatedAt(LocalDateTime.now());
                run.setContextJson(json(Map.of("outputs", outputs)));
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

    private NodeResult callModel(Map<String, Object> node, WorkflowRun run,
                                 List<Map<String, Object>> outputs, JwtPrincipal principal) {
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
        system.append("【用户输入】\n").append(run.getInputText()).append("\n");
        if (!outputs.isEmpty()) {
            system.append("【前序节点结果】\n");
            for (Map<String, Object> o : outputs) {
                system.append("- ").append(o.get("name")).append("：")
                        .append(truncate(str(o.get("output")), 1500)).append("\n");
            }
        }
        String fitted = contextWindow.fitPrompt(modelName, system.toString(), run.getInputText());
        long start = System.currentTimeMillis();
        ChatResponse response = ChatClient.create(modelRouter.resolve(modelName)).prompt()
                .options(OpenAiChatOptions.builder().model(modelName).temperature(0.3).build())
                .system(fitted)
                .user(run.getInputText())
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

    private record NodeResult(String output, long inputTokens, long outputTokens) {
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
        var result = hybridRetrieval.search(run.getTenantId(), query, kbId, topK, threshold, cfg);
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
            if (!List.of("START", "LLM", "AGENT", "TOOL", "KNOWLEDGE", "CONDITION", "APPROVAL", "END").contains(t)) {
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
            } else {
                checkTarget(nodes, str(node.get("next")), "节点 next");
            }
            if ("AGENT".equals(type) && node.get("agentId") == null) {
                throw new BusinessException(400, "AGENT 节点必须选择 Agent");
            }
            if ("TOOL".equals(type) && node.get("toolId") == null) {
                throw new BusinessException(400, "TOOL 节点必须选择工具");
            }
            if ("KNOWLEDGE".equals(type) && node.get("knowledgeBaseId") == null) {
                throw new BusinessException(400, "KNOWLEDGE 节点必须选择知识库");
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
