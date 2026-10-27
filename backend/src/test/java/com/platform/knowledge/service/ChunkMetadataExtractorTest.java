package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMetadataExtractorTest {

    @Test
    void extractsRegulatoryFdaMetadata() {
        String text = "FDA guidance for industry. Methods: dose selection and safety endpoints.";
        ChunkMetadataExtractor.Profile profile = ChunkMetadataExtractor.profile(
                "fda-guidance.pdf", text);
        DocumentParser.ParsedChunk chunk = new DocumentParser.ParsedChunk(
                "Methods\nThis guidance describes dose selection and safety endpoints.",
                3, "Methods\nFDA guidance", null, 1, 1, List.of("Methods"));

        ChunkMetadataExtractor.Metadata meta = ChunkMetadataExtractor.extract(1L, chunk, profile);

        assertEquals("regulatory", meta.documentType());
        assertEquals("FDA", meta.regulatoryCategory());
        assertEquals("methods", meta.sectionType());
        assertEquals(1, meta.chunkLevel());
        assertEquals("1:3", meta.parentKey());
        assertTrue(meta.domainKeywords().stream().anyMatch(k -> k.contains("fda")));
        assertTrue(meta.relevanceScore() > 0.5 && meta.relevanceScore() <= 1.0);
    }

    @Test
    void extractsChineseRegulatoryAndSectionMetadata() {
        String text = "药品注册 监管 指导原则 方法";
        ChunkMetadataExtractor.Profile profile = ChunkMetadataExtractor.profile(
                "指导原则.pdf", text);
        DocumentParser.ParsedChunk chunk = new DocumentParser.ParsedChunk(
                "方法\n本指导原则描述剂量与安全性。", 1, "方法\n监管指导原则",
                null, 1, 1, List.of("方法"));

        ChunkMetadataExtractor.Metadata meta = ChunkMetadataExtractor.extract(9L, chunk, profile);

        assertEquals("regulatory", meta.documentType());
        assertEquals("REGULATORY", meta.regulatoryCategory());
        assertEquals("methods", meta.sectionType());
        assertEquals("9:1", meta.parentKey());
    }

    @Test
    void extractsClinicalTrialAndAbstractMetadata() {
        String text = "A randomized placebo-controlled clinical trial (NCT01234567).";
        ChunkMetadataExtractor.Profile profile = ChunkMetadataExtractor.profile("trial.pdf", text);
        DocumentParser.ParsedChunk chunk = new DocumentParser.ParsedChunk(
                "Abstract\nEfficacy and safety results.", 0, "Abstract\nRandomized clinical trial",
                null, 1, 1, List.of("Abstract"));

        ChunkMetadataExtractor.Metadata meta = ChunkMetadataExtractor.extract(7L, chunk, profile);

        assertEquals("clinical_trial", meta.documentType());
        assertEquals("abstract", meta.sectionType());
        assertTrue(meta.domainKeywords().contains("randomized"));
        assertTrue(meta.domainKeywords().contains("clinical"));
    }
}
