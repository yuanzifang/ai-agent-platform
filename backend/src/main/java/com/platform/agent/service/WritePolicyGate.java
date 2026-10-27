package com.platform.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写工具策略宽松化检测（发布门禁用）。
 *
 * <p>运行时语义：
 * <ul>
 *   <li>writeToolPolicy：BLOCK / APPROVAL / ALLOWLIST / ALLOW_ALL，未配置按 ALLOW_ALL；</li>
 *   <li>allowedWriteToolIds：仅 ALLOWLIST 生效；</li>
 *   <li>writeToolMaxArgs：参数 JSON 字节上限，未配置/非正数按 16384，最大 100000；</li>
 *   <li>writeToolArgRules：工具参数名白名单，空列表视为不限制；</li>
 *   <li>writeToolArgPatterns：工具参数正则，删除/变更无法证明更严格时要求强制发布；</li>
 *   <li>writeToolAmountLimits：工具数值参数上限，删除/提高视为放宽。</li>
 * </ul>
 */
public final class WritePolicyGate {

    private static final int DEFAULT_MAX_ARGS = 16384;
    private static final int MAX_ARGS_CAP = 100000;

    private WritePolicyGate() {
    }

    public record Result(boolean morePermissive, List<String> reasons) {
    }

    public static Result compare(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> oldCaps = before == null ? Map.of() : before;
        Map<String, Object> newCaps = after == null ? Map.of() : after;
        String oldMode = mode(oldCaps);
        String newMode = mode(newCaps);
        int oldRank = rank(oldMode);
        int newRank = rank(newMode);
        List<String> reasons = new ArrayList<>();

        if (newRank > oldRank) {
            reasons.add("模式 " + oldMode + " → " + newMode);
            return new Result(true, reasons);
        }
        if (newRank < oldRank || "BLOCK".equals(newMode) || "APPROVAL".equals(newMode)) {
            return new Result(false, List.of());
        }

        if ("ALLOWLIST".equals(newMode)) {
            compareAllowedTools(oldCaps, newCaps, reasons);
        }
        compareMaxArgs(oldCaps, newCaps, reasons);
        compareArgRules(oldCaps, newCaps, reasons);
        comparePatterns(oldCaps, newCaps, reasons);
        compareAmountLimits(oldCaps, newCaps, reasons);
        return new Result(!reasons.isEmpty(), List.copyOf(reasons));
    }

    private static void compareAllowedTools(Map<String, Object> before, Map<String, Object> after,
                                            List<String> reasons) {
        Set<String> oldIds = toolIds(before.get("allowedWriteToolIds"));
        Set<String> newIds = toolIds(after.get("allowedWriteToolIds"));
        List<String> added = newIds.stream().filter(id -> !oldIds.contains(id)).toList();
        if (!added.isEmpty()) {
            reasons.add("允许工具新增: " + limitedJoin(added));
        }
    }

    private static void compareMaxArgs(Map<String, Object> before, Map<String, Object> after,
                                       List<String> reasons) {
        int oldMax = maxArgs(before.get("writeToolMaxArgs"));
        int newMax = maxArgs(after.get("writeToolMaxArgs"));
        if (newMax > oldMax) {
            reasons.add("参数 JSON 上限 " + oldMax + " → " + newMax);
        }
    }

    private static void compareArgRules(Map<String, Object> before, Map<String, Object> after,
                                        List<String> reasons) {
        Map<String, Set<String>> oldRules = argRules(before.get("writeToolArgRules"));
        Map<String, Set<String>> newRules = argRules(after.get("writeToolArgRules"));
        for (Map.Entry<String, Set<String>> entry : oldRules.entrySet()) {
            String tool = entry.getKey();
            Set<String> oldAllowed = entry.getValue();
            Set<String> newAllowed = newRules.get(tool);
            if (newAllowed == null || newAllowed.isEmpty()) {
                reasons.add("参数白名单移除: 工具 " + tool);
                continue;
            }
            List<String> added = newAllowed.stream().filter(k -> !oldAllowed.contains(k)).toList();
            if (!added.isEmpty()) {
                reasons.add("参数白名单放宽: 工具 " + tool + " 新增 " + limitedJoin(added));
            }
        }
    }

    private static void comparePatterns(Map<String, Object> before, Map<String, Object> after,
                                        List<String> reasons) {
        Map<String, Map<String, String>> oldPatterns = patterns(before.get("writeToolArgPatterns"));
        Map<String, Map<String, String>> newPatterns = patterns(after.get("writeToolArgPatterns"));
        for (Map.Entry<String, Map<String, String>> toolEntry : oldPatterns.entrySet()) {
            String tool = toolEntry.getKey();
            Map<String, String> newFields = newPatterns.get(tool);
            for (Map.Entry<String, String> fieldEntry : toolEntry.getValue().entrySet()) {
                String field = fieldEntry.getKey();
                String oldPattern = fieldEntry.getValue();
                String newPattern = newFields == null ? null : newFields.get(field);
                if (newPattern == null || newPattern.isBlank()) {
                    reasons.add("参数正则移除: 工具 " + tool + "." + field);
                } else if (!newPattern.equals(oldPattern)) {
                    // 无法可靠判断两个正则的包含关系，保守要求强制发布。
                    reasons.add("参数正则变更: 工具 " + tool + "." + field);
                }
            }
        }
    }

    private static void compareAmountLimits(Map<String, Object> before, Map<String, Object> after,
                                            List<String> reasons) {
        Map<String, Map<String, Double>> oldLimits = amountLimits(before.get("writeToolAmountLimits"));
        Map<String, Map<String, Double>> newLimits = amountLimits(after.get("writeToolAmountLimits"));
        for (Map.Entry<String, Map<String, Double>> toolEntry : oldLimits.entrySet()) {
            String tool = toolEntry.getKey();
            Map<String, Double> newFields = newLimits.get(tool);
            for (Map.Entry<String, Double> fieldEntry : toolEntry.getValue().entrySet()) {
                String field = fieldEntry.getKey();
                double oldLimit = fieldEntry.getValue();
                Double newLimit = newFields == null ? null : newFields.get(field);
                if (newLimit == null) {
                    reasons.add("金额上限移除: 工具 " + tool + "." + field);
                } else if (newLimit > oldLimit) {
                    reasons.add("金额上限提高: 工具 " + tool + "." + field
                            + " " + format(oldLimit) + " → " + format(newLimit));
                }
            }
        }
    }

    private static String mode(Map<String, Object> caps) {
        Object raw = caps.get("writeToolPolicy");
        String mode = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "BLOCK", "APPROVAL", "ALLOWLIST", "ALLOW_ALL" -> mode;
            default -> "ALLOW_ALL"; // 与运行时一致：未配置/未知模式按 ALLOW_ALL
        };
    }

    private static int rank(String mode) {
        return switch (mode) {
            case "BLOCK" -> 0;
            case "APPROVAL" -> 1;
            case "ALLOWLIST" -> 2;
            default -> 3;
        };
    }

    private static int maxArgs(Object raw) {
        if (raw instanceof Number n && n.intValue() > 0) {
            return Math.min(n.intValue(), MAX_ARGS_CAP);
        }
        return DEFAULT_MAX_ARGS;
    }

    private static Set<String> toolIds(Object raw) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object id : list) {
                if (id != null && !String.valueOf(id).isBlank()) ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    private static Map<String, Set<String>> argRules(Object raw) {
        Map<String, Set<String>> rules = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return rules;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof List<?> list) || list.isEmpty()) continue;
            Set<String> allowed = new LinkedHashSet<>();
            for (Object key : list) {
                if (key != null && !String.valueOf(key).isBlank()) allowed.add(String.valueOf(key));
            }
            if (!allowed.isEmpty()) rules.put(String.valueOf(entry.getKey()), allowed);
        }
        return rules;
    }

    private static Map<String, Map<String, String>> patterns(Object raw) {
        Map<String, Map<String, String>> patterns = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return patterns;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> fields)) continue;
            Map<String, String> toolPatterns = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                if (field.getValue() == null || String.valueOf(field.getValue()).isBlank()) continue;
                toolPatterns.put(String.valueOf(field.getKey()), String.valueOf(field.getValue()));
            }
            if (!toolPatterns.isEmpty()) patterns.put(String.valueOf(entry.getKey()), toolPatterns);
        }
        return patterns;
    }

    private static Map<String, Map<String, Double>> amountLimits(Object raw) {
        Map<String, Map<String, Double>> limits = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return limits;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> fields)) continue;
            Map<String, Double> toolLimits = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                Double value = number(field.getValue());
                if (value != null) toolLimits.put(String.valueOf(field.getKey()), value);
            }
            if (!toolLimits.isEmpty()) limits.put(String.valueOf(entry.getKey()), toolLimits);
        }
        return limits;
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw == null) return null;
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String limitedJoin(List<String> values) {
        if (values.size() <= 8) return String.join(", ", values);
        return String.join(", ", values.subList(0, 8)) + " 等 " + values.size() + " 项";
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
