package com.platform.eval.service;

import com.platform.eval.entity.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepMetricsTest {

    private EvalResult result(String type, double score, boolean passed) {
        EvalResult result = new EvalResult();
        result.setStepType(type);
        result.setScore(BigDecimal.valueOf(score));
        result.setPassed(passed ? 1 : 0);
        return result;
    }

    @Test
    void summarizesAndDetectsStepRegression() {
        Map<String, Object> baseline = StepMetrics.summarize(List.of(
                result("RETRIEVAL", 1.0, true), result("RETRIEVAL", 1.0, true),
                result("RERANK", 1.0, true)));
        Map<String, Object> current = StepMetrics.summarize(List.of(
                result("RETRIEVAL", 0.5, false), result("RETRIEVAL", 0.5, false),
                result("RERANK", 1.0, true)));

        Map<String, Object> comparison = StepMetrics.compare(current, baseline);
        @SuppressWarnings("unchecked")
        List<String> regressed = (List<String>) comparison.get("regressed");
        assertEquals(List.of("RETRIEVAL"), regressed);
        Map<?, ?> steps = (Map<?, ?>) comparison.get("steps");
        Map<?, ?> retrieval = (Map<?, ?>) steps.get("RETRIEVAL");
        assertEquals(new BigDecimal("-1.0000"), retrieval.get("passRateDelta"));
    }
}
