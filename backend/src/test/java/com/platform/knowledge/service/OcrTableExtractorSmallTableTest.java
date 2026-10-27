package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrTableExtractorSmallTableTest {

    @Test
    void structuresThreeRowTwoColumnTable() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "1\t1\t1\t1\t1\t1\t50\t10\t60\t12\t95\tName\n"
                + "1\t1\t1\t1\t1\t2\t220\t10\t80\t12\t95\tValue\n"
                + "1\t1\t1\t1\t2\t1\t50\t40\t60\t12\t95\tA\n"
                + "1\t1\t1\t1\t2\t2\t220\t40\t80\t12\t95\t1\n"
                + "1\t1\t1\t1\t3\t1\t50\t70\t60\t12\t95\tB\n"
                + "1\t1\t1\t1\t3\t2\t220\t70\t80\t12\t95\t2\n";

        OcrTableExtractor.Result result = OcrTableExtractor.extract(tsv);

        assertTrue(result.table().contains("<table"), result.table());
        assertTrue(result.table().contains("Name") && result.table().contains("Value"), result.table());
    }
}
