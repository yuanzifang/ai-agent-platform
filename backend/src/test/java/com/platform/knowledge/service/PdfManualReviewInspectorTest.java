package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfManualReviewInspectorTest {

    @Test
    void flagsComplexMergedTableAndChartUnverified() {
        List<DocumentParser.ParsedChunk> chunks = List.of(
                new DocumentParser.ParsedChunk(
                        "<table><tr><td rowspan=\"2\">A</td><td>B</td></tr></table>",
                        0, "", null, 1, 1, List.of()),
                new DocumentParser.ParsedChunk(
                        "{\"isChart\":true,\"validationStatus\":\"unverified\"}",
                        1, "", null, 1, 1, List.of()));
        List<String> warnings = new ArrayList<>();

        PdfManualReviewInspector.inspect(chunks, warnings);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("复杂合并单元格")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("图表数据")));
    }

    @Test
    void flagsTableCaptionWithoutStructuredTable() {
        List<String> warnings = new ArrayList<>();
        PdfManualReviewInspector.inspect(List.of(
                new DocumentParser.ParsedChunk("Table 3. Study results are shown below.",
                        0, "", null, 1, 1, List.of())), warnings);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("表格标题")));
    }
}
