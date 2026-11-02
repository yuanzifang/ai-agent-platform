package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeDocumentImage;
import com.platform.knowledge.mapper.KnowledgeDocumentImageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 文档内嵌图片的生命周期管理：解析完成后抽取图片写入对象存储，
 * 删除/重解析文档时同步清理，避免遗留不可达对象。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentMediaStore {

    private final KnowledgeDocumentImageMapper documentImageMapper;
    private final StorageService storage;
    private final DocumentParser parser;

    public void store(KnowledgeDocument doc, byte[] bytes) {
        try {
            delete(doc);
            List<DocumentParser.ExtractedImage> images = parser.extractImages(doc.getFileName(), bytes);
            int index = 1;
            for (DocumentParser.ExtractedImage image : images) {
                String ext = image.extension() == null || image.extension().isBlank()
                        ? "png" : image.extension().toLowerCase(Locale.ROOT);
                if (ext.startsWith(".")) ext = ext.substring(1);
                String objectName = doc.getTenantId() + "/knowledge/" + doc.getKnowledgeBaseId() + "/"
                        + doc.getId() + "/images/" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12) + "." + ext;
                String mime = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                storage.put(objectName, image.data(), mime);
                KnowledgeDocumentImage row = new KnowledgeDocumentImage();
                row.setTenantId(doc.getTenantId());
                row.setDocumentId(doc.getId());
                row.setObjectName(objectName);
                row.setFileName("image-" + index++ + "." + ext);
                row.setMimeType(mime);
                row.setSizeBytes((long) image.data().length);
                row.setCreatedAt(LocalDateTime.now());
                documentImageMapper.insert(row);
            }
        } catch (Exception e) {
            log.warn("文档图片保存失败（不影响正文）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    public void delete(KnowledgeDocument doc) {
        if (doc == null || doc.getId() == null) return;
        List<KnowledgeDocumentImage> rows = documentImageMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentImage>()
                        .eq(KnowledgeDocumentImage::getDocumentId, doc.getId()));
        for (KnowledgeDocumentImage row : rows) {
            storage.remove(row.getObjectName());
        }
        documentImageMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentImage>()
                .eq(KnowledgeDocumentImage::getDocumentId, doc.getId()));
    }
}
