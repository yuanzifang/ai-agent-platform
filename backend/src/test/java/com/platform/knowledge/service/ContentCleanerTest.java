package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentCleanerTest {

    @Test
    void stripsSiteChromeAndKeepsArticleBody() {
        String raw = "中国农业农村信息网_水稻稻瘟病防治 无障碍 农芯慧答 中华人民共和国农业农村部 "
                + "首页 机构 资讯 数据 生产 信息化 专题 视频 首页>生产>专项监测>作物病虫害 "
                + "稻瘟病防治\n发布时间：2024-01-01\n来源：全国农技中心\n\n"
                + "## 防治措施\n\n破口前选用三环唑喷雾。";
        String cleaned = ContentCleaner.clean(raw);

        assertFalse(cleaned.contains("无障碍"));
        assertFalse(cleaned.contains("农芯慧答"));
        assertFalse(cleaned.contains("首页>生产"));
        assertFalse(cleaned.contains("发布时间"));
        assertTrue(cleaned.contains("## 防治措施"));
        assertTrue(cleaned.contains("破口前选用三环唑喷雾"));
    }

    @Test
    void keepsPageAnchorsHeadingsTablesAndCode() {
        String raw = "<!-- page: 1 -->\n\n# 技术方案\n\n"
                + "| 病害 | 药剂 |\n| --- | --- |\n| 稻瘟病 | 三环唑 |\n\n"
                + "```text\nwater\n```";
        String cleaned = ContentCleaner.clean(raw);

        assertTrue(cleaned.contains("<!-- page: 1 -->"));
        assertTrue(cleaned.contains("# 技术方案"));
        assertTrue(cleaned.contains("| 稻瘟病 | 三环唑 |"));
        assertTrue(cleaned.contains("```text"));
    }

    @Test
    void removesStandaloneNavigationLines() {
        String raw = "首页 机构 资讯 数据 生产 信息化 专题 视频\n"
                + "字体：[大 中 小]\n打印\n分享:\n\n正文第一段。";
        String cleaned = ContentCleaner.clean(raw);

        assertFalse(cleaned.contains("首页 机构"));
        assertFalse(cleaned.contains("字体："));
        assertFalse(cleaned.contains("打印"));
        assertTrue(cleaned.contains("正文第一段"));
    }
}
