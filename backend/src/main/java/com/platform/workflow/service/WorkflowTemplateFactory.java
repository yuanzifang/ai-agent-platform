package com.platform.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作流内置模板与默认节点图。 */
@Slf4j
final class WorkflowTemplateFactory {

    private WorkflowTemplateFactory() {
    }

    public static List<Map<String, Object>> templates(ObjectMapper objectMapper) {
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
        // 本地意图分类模板：JSON 资源与前端 E2E fixture 保持同构，便于直接加载和回归。
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(
                "workflow-templates/local-intent-triage.json").getInputStream()) {
            Map<String, Object> localIntent = objectMapper.readValue(in, new TypeReference<>() {});
            list.add(localIntent);
        } catch (Exception e) {
            log.warn("加载本地意图分类工作流模板失败：{}", e.getMessage());
        }
        return list;
    }

    static Map<String, Object> template(String id, String name, String description, List<Map<String, Object>> nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("definition", Map.of("nodes", nodes));
        return m;
    }

    static List<Map<String, Object>> nodes(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }

    static Map<String, Object> knowledge(String id, String next) {
        return WorkflowRetrievalOptions.knowledgeNode(id, next);
    }

    static Map<String, Object> approval(String id, String next) {
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

    static Map<String, Object> parallel(String id, String[] branches, String joinNode) {
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

    static Map<String, Object> join(String id, String next) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "JOIN");
        m.put("name", "汇合");
        m.put("next", next);
        return m;
    }

    static Map<String, Object> tool(String id, Long toolId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", "TOOL");
        m.put("name", "工具调用");
        m.put("argsTemplate", "{}");
        if (toolId != null) m.put("toolId", toolId);
        m.put("next", "llm1");
        return m;
    }

    static Map<String, Object> defaultDefinition() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "START", "开始", "n1", null, null));
        nodes.add(node("n1", "LLM", "生成回答", "end", "根据用户输入给出简洁、准确的回答。", null));
        nodes.add(node("end", "END", "结束", null, null, null));
        return Map.of("nodes", nodes);
    }

    static Map<String, Object> node(String id, String type, String name, String next,
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
}
