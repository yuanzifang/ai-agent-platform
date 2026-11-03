package com.platform.extension.agri;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgriRetrievalPolicyTest {

    private final AgriRetrievalPolicy policy = new AgriRetrievalPolicy();

    @Test
    void stripsSiteChromeButKeepsArticleBody() {
        String raw = "中国农业农村信息网_花生生长中后期病虫害有哪些 怎么防治 无障碍 农芯慧答 "
                + "中华人民共和国农业农村部 首页 机构 资讯 数据 生产 信息化 专题 视频 "
                + "首页>生产>专项监测>作物病虫害 花生中后期主要病害有叶斑病。";
        String cleaned = AgriRetrievalPolicy.cleanSiteChrome(raw);
        assertFalse(cleaned.contains("无障碍"));
        assertFalse(cleaned.contains("首页>"));
        assertTrue(cleaned.contains("花生中后期主要病害"));
    }

    @Test
    void dropsForecastOnlyForActionQueryAndKeepsActionableChunk() {
        Map<String, Object> forecast = Map.of(
                "content", "2024年水稻病虫害发生趋势预测：总体中等发生。",
                "score", 0.8);
        Map<String, Object> actionable = Map.of(
                "content", "水稻稻瘟病防治：破口前选用三环唑喷雾。",
                "score", 0.7);
        var result = policy.filter("水稻稻瘟病怎么防治？",
                Map.of(AgriRetrievalPolicy.CLEAN_GENERIC, true),
                List.of(forecast, actionable));

        assertEquals(1, result.size());
        assertEquals(actionable.get("content"), result.get(0).get("content"));
    }

    @Test
    void keepsForecastForForecastQuestion() {
        Map<String, Object> forecast = Map.of(
                "content", "2024年水稻病虫害发生趋势预测：总体中等发生。",
                "score", 0.8);
        var result = policy.filter("水稻病虫害发生趋势",
                Map.of(AgriRetrievalPolicy.CLEAN_GENERIC, true),
                List.of(forecast));
        assertEquals(1, result.size());
    }

    @Test
    void cleanGenericOffKeepsOriginalHits() {
        Map<String, Object> hit = Map.of(
                "content", "中国农业农村信息网_测试 无障碍 首页 机构 资讯 数据 生产 信息化 专题 视频 正文",
                "score", 0.8);
        var result = policy.filter("水稻稻瘟病怎么防治？", Map.of(), List.of(hit));
        assertEquals(1, result.size());
        assertEquals(hit.get("content"), result.get(0).get("content"));
    }
}
