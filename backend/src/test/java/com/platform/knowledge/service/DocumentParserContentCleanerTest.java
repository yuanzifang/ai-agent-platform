package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserContentCleanerTest {

    @Test
    void parsedMarkdownDoesNotExposeSiteChromeInChunks() {
        String raw = "中国农业农村信息网_水稻稻瘟病防治 无障碍 农芯慧答 "
                + "中华人民共和国农业农村部 首页 机构 资讯 数据 生产 信息化 专题 视频 "
                + "首页>生产>专项监测>作物病虫害\n\n"
                + "## 防治措施\n\n破口前选用三环唑喷雾。";
        DocumentParser parser = new DocumentParser();
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents(
                "article.md", raw.getBytes(StandardCharsets.UTF_8));
        String all = chunks.stream().map(DocumentParser.ParsedChunk::content)
                .reduce("", (a, b) -> a + "\n" + b);

        assertFalse(all.contains("无障碍"));
        assertFalse(all.contains("首页>生产"));
        assertTrue(all.contains("破口前选用三环唑喷雾"));
    }
}
