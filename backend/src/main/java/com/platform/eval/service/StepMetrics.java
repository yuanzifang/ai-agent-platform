package com.platform.eval.service;

import com.platform.eval.entity.EvalResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 步骤级任务指标汇总与回归对比。 */
public final class StepMetrics {

    public static final List<String> TYPES = List.of("ANSWER", "REWRITE", "RETRIEVAL", "RERANK");

    private StepMetrics() {
    }

    public static Map<String, Object> summarize(List<EvalResult> results) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Integer> passed = new LinkedHashMap<>();
        for (EvalResult result : results) {
            String type = typeOf(result);
            counts.computeIfAbsent(type, ignored -> new int[1])[0]++;
            if (result.getScore() != null) scores.merge(type, result.getScore().doubleValue(), Double::sum);
            if (result.getPassed() != null && result.getPassed() == 1) passed.merge(type, 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String type : TYPES) {
            int count = counts.getOrDefault(type, new int[]{0})[0];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("count", count);
            row.put("avgScore", count == 0 ? null : BigDecimal.valueOf(
                    scores.getOrDefault(type, 0.0) / count).setScale(4, RoundingMode.HALF_UP));
            row.put("passRate", count == 0 ? null : BigDecimal.valueOf(
                    (double) passed.getOrDefault(type, 0) / count).setScale(4, RoundingMode.HALF_UP));
            summary.put(type, row);
        }
        return summary;
    }

    /** 对比 current 与 baseline；delta = current - baseline，正数为改善。 */
    public static Map<String, Object> compare(Map<String, Object> current,
                                               Map<String, Object> baseline) {
        Map<String, Object> steps = new LinkedHashMap<>();
        List<String> regressed = new java.util.ArrayList<>();
        List<String> improved = new java.util.ArrayList<>();
        for (String type : TYPES) {
            Map<?, ?> currentRow = current.get(type) instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> baselineRow = baseline.get(type) instanceof Map<?, ?> m ? m : Map.of();
            BigDecimal currentPass = decimal(currentRow.get("passRate"));
            BigDecimal baselinePass = decimal(baselineRow.get("passRate"));
            BigDecimal currentScore = decimal(currentRow.get("avgScore"));
            BigDecimal baselineScore = decimal(baselineRow.get("avgScore"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("current", currentRow);
            row.put("baseline", baselineRow);
            row.put("passRateDelta", delta(currentPass, baselinePass));
            row.put("avgScoreDelta", delta(currentScore, baselineScore));
            steps.put(type, row);
            if (currentPass != null && baselinePass != null) {
                int cmp = currentPass.compareTo(baselinePass);
                if (cmp < 0) regressed.add(type);
                if (cmp > 0) improved.add(type);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", steps);
        result.put("regressed", regressed);
        result.put("improved", improved);
        return result;
    }

    private static String typeOf(EvalResult result) {
        return result.getStepType() == null ? "ANSWER"
                : result.getStepType().toUpperCase(java.util.Locale.ROOT);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }

    private static BigDecimal delta(BigDecimal current, BigDecimal baseline) {
        if (current == null || baseline == null) return null;
        return current.subtract(baseline).setScale(4, RoundingMode.HALF_UP);
    }
}
