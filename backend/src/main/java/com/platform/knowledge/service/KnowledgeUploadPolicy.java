package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 知识库直传元数据校验：扩展名白名单、大小上限和安全文件名处理。 */
@Component
public class KnowledgeUploadPolicy {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "md", "txt", "doc", "docx", "pdf", "xlsx", "xls", "pptx", "ppt",
            "html", "htm", "csv", "json", "epub", "png", "jpg", "jpeg", "bmp", "tif", "tiff");

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

    public String safeFileName(String fileName) {
        String name = fileName == null ? "file" : fileName.trim();
        name = name.replaceAll("[\\\\/\r\n]", "_");
        if (name.isBlank()) name = "file";
        return name.length() > 128 ? name.substring(name.length() - 128) : name;
    }
}
