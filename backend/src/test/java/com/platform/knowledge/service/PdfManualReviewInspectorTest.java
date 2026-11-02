package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PdfManualReviewInspectorTest {

    @Test
    void doesNotTreatDiBiaoNumberAsTableCaption() {
        List<String> warnings = new ArrayList<>();
        PdfManualReviewInspector.inspect(List.of(
                new DocumentParser.ParsedChunk("春季连续5天地表5厘米地温稳定在5℃以上时可适时播种", 0, "")), warnings);
        assertTrue(warnings.isEmpty(), "“地表5厘米”不应被误判为表 5：" + warnings);
    }

    @Test
    void stillFlagsRealTableCaptionWithoutStructuredTable() {
        List<String> warnings = new ArrayList<>();
        PdfManualReviewInspector.inspect(List.of(
                new DocumentParser.ParsedChunk("油菜主产区菌核病调查数据见表1。表1 油菜主产区菌核病调查表", 0, "")),
                warnings);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("检测到表格标题"));
    }
}
