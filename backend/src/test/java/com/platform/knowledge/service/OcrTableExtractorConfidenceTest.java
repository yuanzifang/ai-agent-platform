package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrTableExtractorConfidenceTest {

    @Test
    void preservesAverageTesseractConfidence() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "1\t1\t1\t1\t1\t1\t10\t10\t30\t10\t90\tHello\n"
                + "1\t1\t1\t1\t1\t2\t50\t10\t30\t10\t70\tworld\n";

        OcrTableExtractor.Result result = OcrTableExtractor.extract(tsv);

        assertTrue(result.text().contains("Hello"));
        assertTrue(result.confidence() > 75 && result.confidence() < 85,
                "应保留平均置信度，实际=" + result.confidence());
    }
}
