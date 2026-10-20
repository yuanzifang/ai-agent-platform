package com.platform.knowledge.service;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesParentChildChunks() {
        String text = ("## 章节\n" + "这是一段用于验证父子分块的正文内容。".repeat(20) + "\n").repeat(8);
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("long.md",
                text.getBytes(StandardCharsets.UTF_8));
        assertTrue(chunks.size() > 4);
        assertTrue(chunks.stream().allMatch(c -> c.content().length() <= 500));
        assertTrue(chunks.stream().allMatch(c -> c.parentContent() != null && !c.parentContent().isBlank()));
        assertTrue(chunks.stream().map(DocumentParser.ParsedChunk::parentIndex).distinct().count() > 1);
    }

    @Test
    void ocrImageUsesConfiguredCommand() throws Exception {
        Path script = tempDir.resolve("fake-ocr.sh");
        Files.writeString(script, "#!/bin/sh\necho \"OCR识别文本\"\n", StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        ReflectionTestUtils.setField(parser, "ocrEnabled", true);
        ReflectionTestUtils.setField(parser, "ocrCommand", script.toAbsolutePath().toString());
        ReflectionTestUtils.setField(parser, "ocrLanguages", "eng");
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents("scan.png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        assertTrue(chunks.stream().anyMatch(c -> c.content().contains("OCR识别文本")));
    }

    @Test
    void parsesXlsx() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("产品");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("名称");
            row.createCell(1).setCellValue("价格");
            var row2 = sheet.createRow(1);
            row2.createCell(0).setCellValue("企业版");
            row2.createCell(1).setCellValue("999");
            wb.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("price.xlsx", bytes));
        assertTrue(text.contains("企业版"));
        assertTrue(text.contains("999"));
    }

    @Test
    void parsesPptx() throws Exception {
        byte[] bytes;
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("季度目标：营收增长 30%");
            ppt.write(out);
            bytes = out.toByteArray();
        }
        String text = String.join("\n", parser.parse("plan.pptx", bytes));
        assertTrue(text.contains("季度目标"));
        assertTrue(text.contains("30%"));
    }

    @Test
    void parsesHtmlAndCsv() {
        String html = String.join("\n", parser.parse("manual.html",
                "<html><body><h1>产品手册</h1><p>支持七天无理由退款</p></body></html>"
                        .getBytes(StandardCharsets.UTF_8)));
        assertTrue(html.contains("产品手册"));
        assertTrue(html.contains("七天无理由退款"));

        String csv = String.join("\n", parser.parse("data.csv",
                "姓名,城市\n张三,上海\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(csv.contains("张三"));
        assertTrue(csv.contains("上海"));
    }
}
