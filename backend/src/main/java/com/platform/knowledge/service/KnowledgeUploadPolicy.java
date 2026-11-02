package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

/** 知识库直传元数据校验：扩展名白名单、大小上限和安全文件名处理。 */
@Component
public class KnowledgeUploadPolicy {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "md", "txt", "doc", "docx", "pdf", "xlsx", "xls", "pptx", "ppt",
            "html", "htm", "csv", "json", "epub", "png", "jpg", "jpeg", "bmp", "tif", "tiff");

    public static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    public void validateMetadata(String fileName, Long sizeBytes, int maxBytes) {
        if (sizeBytes != null && sizeBytes > maxBytes) {
            throw new BusinessException(413, "文件不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String name = safeFileName(fileName);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && dot + 1 < name.length()
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(415, "不支持的知识库文件类型：" + (ext.isBlank() ? name : ext));
        }
    }

    /** 展示文件名可能被改成中文标题；解析器需要保留对象存储中的原始扩展名。 */
    public String parserFileName(String displayName, String objectName) {
        if (hasParserExtension(displayName)) return displayName;
        if (hasParserExtension(objectName)) return objectName;
        return displayName;
    }

    private boolean hasParserExtension(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx") || lower.endsWith(".xls")
                || lower.endsWith(".xlsx") || lower.endsWith(".epub") || lower.endsWith(".html")
                || lower.endsWith(".htm") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".log") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    public String safeFileName(String fileName) {
        String name = fileName == null ? "file" : fileName.trim();
        name = name.replaceAll("[\\\\/\r\n]", "_");
        if (name.isBlank()) name = "file";
        return name.length() > 128 ? name.substring(name.length() - 128) : name;
    }
}
