package com.platform.conversation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.knowledge.service.DocumentParser;
import com.platform.knowledge.service.ImageUnderstandingService;
import com.platform.knowledge.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话附件内容注入（需求 3.25 / 3.6）：TXT/MD/DOCX/PDF 提取正文，按文件与总长度截断；
 * 图片走视觉模型识别后注入描述，避免把图片字节当文档解析。解析失败不阻断对话，
 * 单个附件的问题以占位说明呈现给模型。视觉未启用或超限时先做开关判断，
 * 不把图片字节白白从存储拉出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentInjector {

    /** 单条消息附件内容的总字符预算，达到后不再解析后续附件。 */
    static final int TOTAL_BUDGET = 20000;
    /** 单个文件正文的注入上限。 */
    static final int PER_FILE_BUDGET = 8000;
    /** 单张图片描述的注入上限（视觉描述比正文稠密，从紧）。 */
    static final int PER_IMAGE_BUDGET = 4000;

    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final ImageUnderstandingService imageUnderstandingService;

    /** 把附件解析内容拼进用户消息；无附件或 JSON 非法时原样返回。 */
    public String inject(String message, String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) return message;
        try {
            List<Map<String, Object>> list =
                    objectMapper.readValue(attachmentsJson, new TypeReference<>() { });
            if (list.isEmpty()) return message;
            StringBuilder sb = new StringBuilder(message);
            sb.append("\n\n[用户随消息上传了以下附件，内容已解析，请结合附件回答]");
            int total = 0;
            int imageCount = 0;
            for (Map<String, Object> a : list) {
                String name = String.valueOf(a.get("fileName"));
                String objectName = a.get("objectName") == null ? null : String.valueOf(a.get("objectName"));
                String contentType = a.get("contentType") == null ? "" : String.valueOf(a.get("contentType"));
                // 文件名进的是模型 prompt：剥掉尖括号/引号，防止 </untrusted_image> 提前闭合包裹
                String tagName = sanitizeTagName(name);
                sb.append("\n--- 附件：").append(tagName).append(" ---\n");
                if (objectName == null || total >= TOTAL_BUDGET) {
                    sb.append("（文件内容未解析或已达上下文上限）");
                    continue;
                }
                try {
                    boolean image = contentType.toLowerCase(Locale.ROOT).startsWith("image/");
                    if (image && !imageUnderstandingService.enabled()) {
                        sb.append("（图片视觉识别未启用或失败，未提取到图片内容）");
                        continue;
                    }
                    if (image && imageCount >= imageUnderstandingService.maxImages()) {
                        sb.append("（图片数量超过视觉识别上限，未识别）");
                        continue;
                    }
                    byte[] bytes = storageService.get(objectName);
                    if (image) {
                        imageCount++;
                        String description = imageUnderstandingService.describeForChat(bytes, name);
                        if (description == null || description.isBlank()) {
                            sb.append("（图片视觉识别未启用或失败，未提取到图片内容）");
                        } else {
                            String clipped = clip(description, PER_IMAGE_BUDGET, TOTAL_BUDGET - total);
                            sb.append("\n<untrusted_image name=\"").append(tagName)
                                    .append("\">\n").append(clipped).append("\n</untrusted_image>");
                            total += clipped.length();
                        }
                        continue;
                    }
                    List<String> chunks = documentParser.parse(name, bytes);
                    String text = String.join("\n", chunks);
                    if (text.isBlank()) {
                        sb.append("（未提取到文本内容）");
                    } else {
                        String clipped = clip(text, PER_FILE_BUDGET, TOTAL_BUDGET - total);
                        sb.append("\n<untrusted_attachment name=\"").append(tagName)
                                .append("\">\n").append(clipped).append("\n</untrusted_attachment>");
                        total += clipped.length();
                    }
                } catch (Exception e) {
                    log.warn("附件解析失败 file={}：{}", name, e.getMessage());
                    sb.append("（附件解析失败：").append(e.getMessage()).append("）");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return message;
        }
    }

    private static String clip(String text, int perFileLimit, int remainBudget) {
        int limit = Math.min(perFileLimit, remainBudget);
        if (limit <= 0) return "";
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    /** 文件名拼进 untrusted 包裹标签前剥掉 < > "，防止提前闭合包裹注入伪造内容。 */
    private static String sanitizeTagName(String name) {
        return name.replaceAll("[<>\"]", "_");
    }
}
