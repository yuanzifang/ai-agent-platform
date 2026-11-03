package com.platform.extension.agri;

import com.platform.extension.retrieval.RetrievalPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 农业检索策略的可选扩展。
 *
 * <p>查询归一化和作物范围过滤只在 Agent 的知识库绑定配置显式开启
 * {@code normalizeQuery=true} / {@code cropScope=true} 时生效；平台核心检索
 * 不包含作物词表，也不依赖该扩展。</p>
 */
@Service
public class AgriRetrievalPolicy implements RetrievalPolicy {

    public static final String NORMALIZE_QUERY = "normalizeQuery";
    public static final String CROP_SCOPE = "cropScope";
    public static final String CLEAN_GENERIC = "cleanGeneric";

    private static final Pattern SITE_PREFIX = Pattern.compile(
            "^中国农业农村信息网_.*?视频\\s*(?:首页>[^\\s]*\\s*)?", Pattern.DOTALL);
    private static final Pattern FORECAST = Pattern.compile(
            "预测|趋势|发生动态|总体中等|偏重发生|发生面积|发生程度");
    private static final Pattern ACTION = Pattern.compile(
            "防治|防控|用药|施药|喷施|喷雾|田间管理|栽培|选用|品种|剂量");

    private static final Map<String, Pattern> CROP_PATTERNS = cropPatterns();

    @Override
    public String id() {
        return "agri-retrieval";
    }

    @Override
    public String normalizeQuery(String query, Map<String, Object> config) {
        if (!enabled(config, NORMALIZE_QUERY)) return query;
        return normalizeRetrievalQuery(query);
    }

    @Override
    public List<Map<String, Object>> filter(String query, Map<String, Object> config,
                                            List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) return hits;
        List<Map<String, Object>> working = hits;
        if (enabled(config, CLEAN_GENERIC)) {
            working = cleanGenericHits(query, hits);
        }
        if (!enabled(config, CROP_SCOPE)) return working;
        String crop = detectCrop(query);
        if (crop == null) return working;
        List<Map<String, Object>> scoped = new ArrayList<>();
        for (Map<String, Object> hit : working) {
            if (containsCrop(String.valueOf(hit.getOrDefault("content", "")), crop)) {
                scoped.add(hit);
            }
        }
        // 没有作物命中的召回时保留原结果，避免过滤后空上下文。
        return scoped.isEmpty() ? working : scoped;
    }

    private List<Map<String, Object>> cleanGenericHits(String query, List<Map<String, Object>> hits) {
        boolean actionQuery = query != null && Pattern.compile(
                "防治|防控|怎么办|怎么治|如何治|用药|打药|管理|补救|恢复").matcher(query).find();
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            String content = cleanSiteChrome(str(hit.get("content")));
            if (content.isBlank()) continue;
            // “怎么防治”类问题不应把纯趋势预测片段排进上下文；趋势查询保留。
            if (actionQuery && FORECAST.matcher(content).find() && !ACTION.matcher(content).find()) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(hit);
            copy.put("content", content);
            Object parent = hit.get("parentContent");
            if (parent != null) copy.put("parentContent", cleanSiteChrome(str(parent)));
            cleaned.add(copy);
        }
        // 全部被清洗时保留原结果，避免空上下文；正常情况返回清洗后的候选。
        return cleaned.isEmpty() ? hits : cleaned;
    }

    /** 去掉农业农村信息网首块的站点导航；正文从标题/正文开始。 */
    static String cleanSiteChrome(String content) {
        if (content == null || content.isBlank()) return "";
        String cleaned = SITE_PREFIX.matcher(content).replaceFirst("").trim();
        return cleaned.isBlank() ? "" : cleaned;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean enabled(Map<String, Object> config, String key) {
        if (config == null) return false;
        Object value = config.get(key);
        return Boolean.TRUE.equals(value)
                || "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private static Map<String, Pattern> cropPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("水稻", Pattern.compile("水稻|稻谷|稻田|稻瘟病|稻飞虱|稻曲病|稻纵卷叶螟|稻蓟马|稻"));
        patterns.put("小麦", Pattern.compile("小麦|冬小麦|麦田|麦苗|麦"));
        patterns.put("玉米", Pattern.compile("玉米|苞米|苞谷"));
        patterns.put("大豆", Pattern.compile("大豆|黄豆"));
        patterns.put("油菜", Pattern.compile("油菜|油菜籽"));
        patterns.put("花生", Pattern.compile("花生"));
        patterns.put("马铃薯", Pattern.compile("马铃薯|土豆|种薯"));
        patterns.put("番茄", Pattern.compile("番茄|西红柿"));
        patterns.put("黄瓜", Pattern.compile("黄瓜"));
        patterns.put("豇豆", Pattern.compile("豇豆"));
        patterns.put("芹菜", Pattern.compile("芹菜"));
        patterns.put("韭菜", Pattern.compile("韭菜"));
        patterns.put("苹果", Pattern.compile("苹果"));
        patterns.put("柑橘", Pattern.compile("柑橘|橘|橙|柚"));
        patterns.put("葡萄", Pattern.compile("葡萄"));
        patterns.put("茶树", Pattern.compile("茶树|茶园|茶叶|茶"));
        patterns.put("蔬菜", Pattern.compile("蔬菜|菜田|菜农|菜地"));
        return java.util.Collections.unmodifiableMap(patterns);
    }

    /** 轻量作物识别；保留为 public 便于业务扩展单测。 */
    public static String detectCrop(String query) {
        if (query == null || query.isBlank()) return null;
        for (Map.Entry<String, Pattern> entry : CROP_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(query).find()) return entry.getKey();
        }
        return null;
    }

    private static boolean containsCrop(String content, String crop) {
        if (content == null || content.isBlank()) return false;
        Pattern pattern = CROP_PATTERNS.get(crop);
        return pattern != null && pattern.matcher(content).find();
    }

    /** 去掉问句套话，保留作物/病害/措施等实体词。仅在 normalizeQuery=true 时启用。 */
    public static String normalizeRetrievalQuery(String query) {
        if (query == null || query.isBlank()) return "";
        String q = query.replaceAll(
                "(请问|请帮我|帮我|帮忙|想问|想了解|一下|怎么办|怎么防治|如何防治|怎样防治"
                        + "|怎么防控|如何防控|怎样防控|怎么预防|如何预防|怎样预防"
                        + "|怎么治|如何治|怎样治|怎么处理|如何处理|怎样处理"
                        + "|怎么改良|如何改良|怎么改进|如何改进|怎么防范|如何防范"
                        + "|怎么应对|如何应对|怎样应对|防治方法|防治措施|防治办法"
                        + "|防控措施|注意事项|注意什么|需要注意什么|有哪些|是什么"
                        + "|怎么|如何|怎样|咋)", " ");
        q = q.replaceAll("[\\s，,。！？?；;：:、]+", " ").trim();
        return q;
    }
}
