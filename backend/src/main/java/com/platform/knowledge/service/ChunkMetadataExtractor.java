package com.platform.knowledge.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式块元数据抽取：文档类型、章节、层级、父子键、领域关键词、静态相关性、监管类别。
 * 不调用模型，结果可测试、可复现；后续可替换为分类器但字段契约不变。
 */
final class ChunkMetadataExtractor {

    record Profile(String documentType, String regulatoryCategory) { }

    record Metadata(String documentType, String sectionType, int chunkLevel, String parentKey,
                    List<String> domainKeywords, double relevanceScore, String regulatoryCategory) { }

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9-]{3,}");
    private static final Set<String> STOP = Set.of(
            "that", "with", "from", "this", "have", "were", "which", "their", "there", "been",
            "into", "than", "also", "more", "such", "using", "between", "these", "other", "when",
            "where", "while", "each", "both", "does", "only", "over", "under", "about", "after");
    private static final Set<String> KNOWN_TERMS = new LinkedHashSet<>(Arrays.asList(
            "randomized", "placebo", "clinical", "trial", "efficacy", "safety", "adverse",
            "patients", "dose", "dosage", "outcome", "endpoint", "biomarker", "diagnosis",
            "treatment", "therapy", "mortality", "incidence", "fda", "ema", "ich",
            "regulation", "guideline", "compliance", "validation", "pharmacokinetics",
            "适应症", "临床试验", "不良反应", "有效性", "安全性", "监管", "指南", "剂量",
            "随机", "安慰剂", "患者", "疗效", "终点", "诊断", "治疗", "药代动力学"));
    private static final Map<String, Double> SECTION_WEIGHTS = Map.ofEntries(
            Map.entry("abstract", 1.0), Map.entry("summary", 0.95),
            Map.entry("results", 0.9), Map.entry("conclusion", 0.9),
            Map.entry("discussion", 0.8), Map.entry("methods", 0.7),
            Map.entry("introduction", 0.6), Map.entry("background", 0.6),
            Map.entry("references", 0.2), Map.entry("body", 0.5));

    private ChunkMetadataExtractor() { }

    static Profile profile(String fileName, String sampleContent) {
        String text = ((fileName == null ? "" : fileName) + " " + (sampleContent == null ? "" : sampleContent))
                .toLowerCase(Locale.ROOT);
        String regulatory = regulatoryCategory(text);
        if (regulatory == null && containsAny(text, "监管", "指导原则", "法规", "药品注册")) {
            regulatory = "REGULATORY";
        }
        String type;
        if (regulatory != null) {
            type = "regulatory";
        } else if (text.matches("(?s).*\\b(randomi[sz]ed|placebo|clinical trial|phase\\s+(i|ii|iii|iv)|nct\\d+)\\b.*")
                || containsAny(text, "临床试验", "随机", "安慰剂", "受试者")) {
            type = "clinical_trial";
        } else if (text.matches("(?s).*\\b(abstract|doi|references|et al\\.|journal|research article)\\b.*")
                || containsAny(text, "摘要", "参考文献", "研究论文")) {
            type = "research_paper";
        } else {
            type = "general";
        }
        return new Profile(type, regulatory);
    }

    static Metadata extract(Long documentId, DocumentParser.ParsedChunk chunk, Profile profile) {
        String content = chunk.content() == null ? "" : chunk.content();
        String parent = chunk.parentContent() == null ? "" : chunk.parentContent();
        List<String> path = chunk.headingPath() == null ? List.of() : chunk.headingPath();
        String section = sectionType(path, content, parent);
        int level = path.isEmpty() ? 1 : Math.max(1, Math.min(4, path.size()));
        String parentKey = documentId + ":" + chunk.parentIndex();
        List<String> keywords = keywords(content + "\n" + parent);
        double score = relevance(section, keywords, content);
        return new Metadata(profile.documentType(), section, level, parentKey, keywords, score,
                profile.regulatoryCategory());
    }

    private static String regulatoryCategory(String text) {
        if (text.matches("(?s).*\\b(fda|21\\s*cfr|food and drug administration|cder|cber)\\b.*")) return "FDA";
        if (text.matches("(?s).*\\b(ema|european medicines agency|eu mdr)\\b.*")) return "EMA";
        if (text.matches("(?s).*\\b(ich|international council for harmonisation)\\b.*")) return "ICH";
        return null;
    }

    private static String sectionType(List<String> path, String content, String parent) {
        String haystack = (path.isEmpty() ? "" : String.join(" ", path)) + "\n"
                + prefix(content, 300) + "\n" + prefix(parent, 300);
        String lower = haystack.toLowerCase(Locale.ROOT);
        String[][] rules = {
                {"abstract", "abstract|summary|摘要|概要"},
                {"introduction", "introduction|background|引言|背景"},
                {"methods", "methods|materials and methods|methodology|方法|材料与方法"},
                {"results", "results|findings|结果"},
                {"discussion", "discussion|讨论"},
                {"conclusion", "conclusion|conclusions|结论"},
                {"references", "references|bibliography|参考文献"}
        };
        for (String[] rule : rules) {
            if (containsAny(lower, rule[1].split("\\|"))) return rule[0];
        }
        return "body";
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term)) return true;
        }
        return false;
    }

    private static List<String> keywords(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            if (word.length() < 4 || STOP.contains(word) || word.matches("\\d+")) continue;
            counts.merge(word, 1, Integer::sum);
        }
        for (String term : KNOWN_TERMS) {
            if ((text == null ? "" : text).toLowerCase(Locale.ROOT).contains(term)) {
                counts.merge(term, 50, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8).map(Map.Entry::getKey).toList();
    }

    private static double relevance(String section, List<String> keywords, String content) {
        double base = SECTION_WEIGHTS.getOrDefault(section, 0.5);
        double keywordBoost = Math.min(0.15, keywords.size() * 0.02);
        int length = content == null ? 0 : content.length();
        double lengthBoost = length >= 200 && length <= 1500 ? 0.05 : 0;
        return Math.round(Math.min(1.0, base + keywordBoost + lengthBoost) * 1000d) / 1000d;
    }

    private static String prefix(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
