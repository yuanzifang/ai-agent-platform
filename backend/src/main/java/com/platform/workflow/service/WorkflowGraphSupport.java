package com.platform.workflow.service;

import com.platform.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工作流图归一化、校验和条件判断的纯函数。 */
final class WorkflowGraphSupport {

    private WorkflowGraphSupport() {
    }

    private static final Set<String> NODE_TYPES = Set.of("START", "LLM", "AGENT", "TOOL",
            "KNOWLEDGE", "CONDITION", "APPROVAL", "PARALLEL", "LOOP", "JOIN", "HANDOFF", "STATE", "END");

    /** 并行节点：分支用独立线程执行，结果文本汇总后进入 JOIN。 */
    static boolean isSimpleNode(String type) {
        return "LLM".equals(type) || "AGENT".equals(type) || "HANDOFF".equals(type)
                || "TOOL".equals(type) || "KNOWLEDGE".equals(type);
    }

    static void normalizeGraph(Map<String, Object> definition) {
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

    static void validate(Map<String, Object> definition) {
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
                String source = str(node.getOrDefault("conditionSource", "last"));
                if (!List.of("last", "state", "input").contains(source)) {
                    throw new BusinessException(400, "条件判断来源仅支持 last/state/input");
                }
                if ("state".equals(source) && (node.get("conditionStateKey") == null
                        || str(node.get("conditionStateKey")).isBlank())) {
                    throw new BusinessException(400, "条件判断来源为共享状态时必须配置 conditionStateKey");
                }
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

    static void checkTarget(Map<String, Map<String, Object>> nodes, String id, String label) {
        if (id == null || id.isBlank() || !nodes.containsKey(id)) {
            throw new BusinessException(400, label + " 指向的节点不存在：" + id);
        }
    }

    static Map<String, Object> findNode(Map<String, Object> definition, String nodeId) {
        Object raw = definition.get("nodes");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && nodeId.equals(str(m.get("id")))) return castMap(m);
            }
        }
        return null;
    }

    static Map<String, Map<String, Object>> nodeMap(Map<String, Object> definition) {
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

    static boolean evaluate(Map<String, Object> node, String value,
                             Map<String, Object> state, String input) {
        String source = str(node.getOrDefault("conditionSource", "last"));
        if ("state".equalsIgnoreCase(source)) {
            String key = str(node.get("conditionStateKey"));
            value = key == null || key.isBlank() || state == null
                    ? "" : String.valueOf(state.getOrDefault(key, ""));
        } else if ("input".equalsIgnoreCase(source)) {
            value = input == null ? "" : input;
        }
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

}
