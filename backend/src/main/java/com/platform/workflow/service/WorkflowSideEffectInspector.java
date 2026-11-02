package com.platform.workflow.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 恢复工作流前的副作用检查：LOOP/PARALLEL 内部也可能嵌套 TOOL/HANDOFF/AGENT。 */
final class WorkflowSideEffectInspector {

    private WorkflowSideEffectInspector() {
    }

    static boolean nestedMayHaveSideEffects(Map<String, Object> definition, Map<String, Object> node) {
        if (node == null) return false;
        String type = str(node.get("type")).toUpperCase();
        Set<String> visited = new HashSet<>();
        if ("LOOP".equals(type)) {
            return fromNode(definition, str(node.get("bodyNode")), visited);
        }
        if ("PARALLEL".equals(type) && node.get("branches") instanceof List<?> branches) {
            for (Object branch : branches) {
                if (fromNode(definition, str(branch), visited)) return true;
            }
        }
        return false;
    }

    private static boolean fromNode(Map<String, Object> definition, String id, Set<String> visited) {
        if (id == null || id.isBlank() || !visited.add(id)) return false;
        Map<String, Object> node = findNode(definition, id);
        if (node == null) return false;
        String type = str(node.get("type")).toUpperCase();
        if (Set.of("TOOL", "HANDOFF", "AGENT").contains(type)) return true;
        if ("LOOP".equals(type)) return fromNode(definition, str(node.get("bodyNode")), visited);
        if ("PARALLEL".equals(type) && node.get("branches") instanceof List<?> branches) {
            for (Object branch : branches) {
                if (fromNode(definition, str(branch), visited)) return true;
            }
            return false;
        }
        if ("CONDITION".equals(type)) {
            return fromNode(definition, str(node.get("trueNext")), visited)
                    || fromNode(definition, str(node.get("falseNext")), visited);
        }
        return fromNode(definition, str(node.get("next")), visited);
    }

    private static Map<String, Object> findNode(Map<String, Object> definition, String id) {
        if (definition == null || id == null) return null;
        Object raw = definition.get("nodes");
        if (!(raw instanceof List<?> nodes)) return null;
        for (Object item : nodes) {
            if (item instanceof Map<?, ?> map && id.equals(str(map.get("id")))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> node = (Map<String, Object>) map;
                return node;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
