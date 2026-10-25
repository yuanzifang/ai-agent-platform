package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChartDataValidatorTest {

    @Test
    void acceptsSeriesPointsAndNumericStrings() {
        String content = "【图表数据·人工修正】{\"isChart\":true,\"series\":["
                + "{\"name\":\"Sales\",\"points\":[{\"label\":\"Q1\",\"value\":\"120\"},"
                + "{\"label\":\"Q2\",\"value\":130}]}]}";
        assertNull(ChartDataValidator.validate(content));
    }

    @Test
    void acceptsSimpleNumericSeries() {
        assertNull(ChartDataValidator.validate("【图表数据】{\"series\":[1,2,3]}"));
    }

    @Test
    void rejectsBrokenJsonAndNonNumericValues() {
        assertNotNull(ChartDataValidator.validate("【图表数据】{\"series\":["));
        assertNotNull(ChartDataValidator.validate(
                "【图表数据】{\"series\":[{\"points\":[{\"value\":\"abc\"}]}]}"));
        assertNotNull(ChartDataValidator.validate("【图表数据】{\"series\":{}}"));
    }

    @Test
    void rejectsMissingMarkerAndStructure() {
        assertNotNull(ChartDataValidator.validate("{\"series\":[1]}"));
        assertNotNull(ChartDataValidator.validate("【图表数据】{}"));
    }
}
