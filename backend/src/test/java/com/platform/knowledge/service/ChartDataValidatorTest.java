package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void crossChecksValuesAgainstDocumentEvidence() throws Exception {
        JsonNode chart = new ObjectMapper().readTree(
                "{\"series\":[{\"points\":[{\"value\":\"1999\"},{\"value\":\"99\"}]}]}");

        assertEquals("verified", ChartDataValidator.crossCheck(chart,
                "Water 1,999\nFilter 99").status());
        assertEquals("conflict", ChartDataValidator.crossCheck(chart,
                "Water 300\nFilter 99").status());
        assertEquals("unverified", ChartDataValidator.crossCheck(chart, "no numbers").status());
    }

    @Test
    void rejectsMissingMarkerAndStructure() {
        assertNotNull(ChartDataValidator.validate("{\"series\":[1]}"));
        assertNotNull(ChartDataValidator.validate("【图表数据】{}"));
    }
}
