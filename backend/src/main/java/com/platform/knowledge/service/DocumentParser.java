package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档解析与分段（架构 6.1/6.2/6.3）：MD/TXT 直读、DOCX(POI)、PDF(PDFBox)；
 * 分段按字符数（默认 500）带重叠（默认 50）。
 */
@Slf4j
@Service
public class DocumentParser {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final long MAX_BYTES = 20 * 1024 * 1024;

    public List<String> parse(String fileName, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new BusinessException(400, "文件超过 20MB 限制");
        String lower = fileName.toLowerCase(Locale.ROOT);
        String text;
        try {
            if (lower.endsWith(".docx")) text = parseDocx(bytes);
            else if (lower.endsWith(".pdf")) text = parsePdf(bytes);
            else if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".markdown"))
                text = new String(bytes, StandardCharsets.UTF_8);
            else throw new BusinessException(400, "不支持的格式（支持 DOCX/PDF/TXT/MD）：" + fileName);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(422, "文档解析失败：" + e.getMessage());
        }
        if (text == null || text.isBlank()) throw new BusinessException(422, "文档内容为空");
        return split(text);
    }

    private String parseDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                table.getRows().forEach(row -> {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String t = cell.getText();
                        cells.add(t == null ? "" : t.trim());
                    }
                    sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                });
            }
        }
        return sb.toString();
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** 按字符分段，带重叠（架构 6.3 splitByCharacter）。 */
    private List<String> split(String text) {
        String normalized = text.replaceAll("\\r\\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) break;
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
