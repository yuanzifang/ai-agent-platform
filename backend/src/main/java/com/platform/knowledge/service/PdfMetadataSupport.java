package com.platform.knowledge.service;

import com.platform.common.context.TenantContext;
import com.platform.knowledge.entity.KnowledgeOcrCache;
import com.platform.knowledge.mapper.KnowledgeOcrCacheMapper;
import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xddf.usermodel.chart.XDDFChart;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import jakarta.annotation.PreDestroy;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import java.util.function.BiFunction;
import java.util.function.Consumer;

@Slf4j
final class PdfMetadataSupport {

    private static final ThreadLocal<Integer> ATTACHMENT_DEPTH = ThreadLocal.withInitial(() -> 0);

    private PdfMetadataSupport() {
    }

    /** PDF 元数据：标题/作者/主题/关键词/页数，便于检索与溯源。 */
    static void appendPdfMetadata(PDDocument doc, StringBuilder sb) {
        try {
            var info = doc.getDocumentInformation();
            StringBuilder meta = new StringBuilder();
            appendMeta(meta, "标题", info.getTitle());
            appendMeta(meta, "作者", info.getAuthor());
            appendMeta(meta, "主题", info.getSubject());
            appendMeta(meta, "关键词", info.getKeywords());
            if (meta.length() > 0) {
                sb.append("\n[PDF元数据] 页数: ").append(doc.getNumberOfPages()).append("; ")
                        .append(meta).append('\n');
            }
        } catch (Exception e) {
            log.debug("PDF 元数据读取失败（忽略）：{}", e.getMessage());
        }
    }

    static void appendMeta(StringBuilder meta, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (meta.length() > 0) meta.append("; ");
        meta.append(label).append(": ").append(value.trim());
    }

    static int resolveDestinationPage(PDDocument doc,
                                       org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination destination) {
        try {
            if (destination instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination pageDest
                    && pageDest.getPage() != null) {
                return doc.getPages().indexOf(pageDest.getPage()) + 1;
            }
            if (destination instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination named) {
                var names = doc.getDocumentCatalog().getNames();
                if (names == null || names.getDests() == null) return -1;
                var pageDest = names.getDests().getValue(named.getNamedDestination());
                if (pageDest != null && pageDest.getPage() != null) {
                    return doc.getPages().indexOf(pageDest.getPage()) + 1;
                }
            }
        } catch (Exception e) {
            log.debug("PDF 命名目标解析失败（忽略）：{}", e.getMessage());
        }
        return -1;
    }

    /** PDF 书签目录与超链接：保留文档结构和外部链接，便于检索/跳转。 */
    static void appendPdfOutlineAndLinks(PDDocument doc, StringBuilder sb) {
        try {
            var outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                int[] count = {0};
                appendOutlineItems(outline.getFirstChild(), sb, count, doc);
            }
        } catch (Exception e) {
            log.debug("PDF 书签读取失败（忽略）：{}", e.getMessage());
        }
        try {
            for (var page : doc.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    if (!(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link)) continue;
                    var action = link.getAction();
                    if (action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri
                            && uri.getURI() != null && !uri.getURI().isBlank()) {
                        sb.append("\n[链接]").append(uri.getURI().trim()).append('\n');
                    } else if (action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo goTo) {
                        int number = resolveDestinationPage(doc, goTo.getDestination());
                        if (number > 0) sb.append("\n[跳转]第").append(number).append("页\n");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PDF 链接读取失败（忽略）：{}", e.getMessage());
        }
    }

    static void appendOutlineItems(org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item,
                                    StringBuilder sb, int[] count, PDDocument doc) {
        if (item == null || count[0] >= 100) return;
        try {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                sb.append("\n[书签]").append(title.trim());
                try {
                    var page = item.findDestinationPage(doc);
                    if (page != null) {
                        int number = doc.getPages().indexOf(page) + 1;
                        if (number > 0) sb.append("（第").append(number).append("页）");
                    }
                } catch (Exception ignored) {
                }
                sb.append('\n');
                count[0]++;
            }
            appendOutlineItems(item.getFirstChild(), sb, count, doc);
            appendOutlineItems(item.getNextSibling(), sb, count, doc);
        } catch (Exception ignored) {
        }
    }

    /** PDF 表单字段/批注：表单值不进入正文文本层时，作为补充知识进入解析结果。 */
    static void appendPdfFormsAndAnnotations(PDDocument doc, StringBuilder sb, Consumer<String> warning) {
        try {
            var acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                for (var field : acroForm.getFieldTree()) {
                    try {
                        String value = field.getValueAsString();
                        String name = field.getFullyQualifiedName();
                        if (name != null && value != null && !value.isBlank()) {
                            sb.append("\n[表单]").append(name.trim()).append(": ").append(value.trim()).append('\n');
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (acroForm.hasXFA()) {
                    try {
                        byte[] xfa = acroForm.getXFA().getBytes();
                        if (xfa != null && xfa.length > 0) {
                            sb.append(parseXfa(xfa));
                            warning.accept("PDF 含 XFA 表单：已提取数据/字段，未渲染 Adobe 动态布局");
                        }
                    } catch (Exception e) {
                        log.debug("PDF XFA 读取失败（忽略）：{}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PDF 表单字段读取失败（忽略）：{}", e.getMessage());
        }
        try {
            for (var page : doc.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    try {
                        String contents = annotation.getContents();
                        if (contents == null || contents.isBlank()) continue;
                        String subtype = annotation.getSubtype();
                        sb.append("\n[批注]").append(subtype == null ? "ANNOTATION" : subtype)
                                .append(": ").append(contents.trim()).append('\n');
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PDF 批注读取失败（忽略）：{}", e.getMessage());
        }
    }

    /** XFA 原文 + 动态数据集路径；渲染型版面仍无法在服务端完整还原。 */
    static String parseXfa(byte[] bytes) {
        String xml = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        try {
            String text = Jsoup.parse(xml, "", Parser.xmlParser()).text();
            if (!text.isBlank()) {
                out.append("\n[XFA]").append(text, 0, Math.min(text.length(), 8000)).append('\n');
            }
        } catch (Exception ignored) {
            // 原文解析失败时仍尝试 DOM 数据集提取
        }
        List<String> fields = xfaFields(xml);
        if (!fields.isEmpty()) {
            out.append("\n[XFA数据]\n");
            for (String field : fields) out.append(field).append('\n');
        }
        List<String> templateFields = xfaTemplateFields(xml);
        if (!templateFields.isEmpty()) {
            out.append("\n[XFA字段]\n");
            for (String field : templateFields) out.append(field).append('\n');
        }
        return out.toString();
    }

    static List<String> xfaFields(String xml) {
        List<String> fields = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document dom = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList datasets = dom.getElementsByTagNameNS("*", "datasets");
            if (datasets.getLength() == 0) datasets = dom.getElementsByTagNameNS("*", "data");
            if (datasets.getLength() == 0) return fields;
            Element start = (Element) datasets.item(0);
            Element data = firstChild(start, "data");
            if (data != null) start = data;
            NodeList children = start.getChildNodes();
            for (int i = 0; i < children.getLength() && fields.size() < 200; i++) {
                if (children.item(i) instanceof Element child) collectXfa(child, "", fields);
            }
        } catch (Exception e) {
            log.debug("XFA 数据集解析失败（忽略）：{}", e.getMessage());
        }
        return fields;
    }

    static void collectXfa(Element element, String prefix, List<String> fields) {
        if (fields.size() >= 200) return;
        String name = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        int occurrence = 1;
        Node sibling = element.getPreviousSibling();
        while (sibling != null) {
            if (sibling instanceof Element prev) {
                String prevName = prev.getLocalName() == null ? prev.getNodeName() : prev.getLocalName();
                if (name.equals(prevName)) occurrence++;
            }
            sibling = sibling.getPreviousSibling();
        }
        String segment = name + (occurrence > 1 ? "[" + occurrence + "]" : "");
        String path = prefix.isEmpty() ? segment : prefix + "." + segment;
        boolean hasElementChild = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                hasElementChild = true;
                collectXfa(child, path, fields);
            }
        }
        if (!hasElementChild) {
            String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
            if (!value.isBlank()) {
                if (value.length() > 500) value = value.substring(0, 500);
                fields.add(path + ": " + value);
            }
        }
    }

    /** 提取 XFA template 中字段路径/标签，适用于 datasets 为空的动态表单。 */
    static List<String> xfaTemplateFields(String xml) {
        List<String> fields = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document dom = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList fieldNodes = dom.getElementsByTagNameNS("*", "field");
            for (int i = 0; i < fieldNodes.getLength() && fields.size() < 200; i++) {
                if (!(fieldNodes.item(i) instanceof Element field)) continue;
                String name = field.getAttribute("name");
                if (name == null || name.isBlank()) {
                    String ref = textOfFirst(field, "ref");
                    name = ref == null ? "" : ref;
                }
                if (name.isBlank()) continue;
                String path = xfaFieldPath(field);
                String caption = textOfFirst(field, "caption");
                fields.add(path + (caption == null || caption.isBlank() ? "" : " (" + caption + ")"));
            }
        } catch (Exception e) {
            log.debug("XFA template 字段解析失败（忽略）：{}", e.getMessage());
        }
        return fields;
    }

    static String xfaFieldPath(Element field) {
        java.util.ArrayDeque<String> names = new java.util.ArrayDeque<>();
        String fieldName = field.getAttribute("name");
        if (fieldName != null && !fieldName.isBlank()) names.addFirst(fieldName);
        Node parent = field.getParentNode();
        while (parent instanceof Element element) {
            String local = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
            if ("subform".equals(local)) {
                String name = element.getAttribute("name");
                if (name != null && !name.isBlank()) names.addFirst(name);
            }
            parent = parent.getParentNode();
        }
        return String.join(".", names);
    }

    static String textOfFirst(Element root, String localName) {
        NodeList all = root.getElementsByTagNameNS("*", localName);
        if (all.getLength() == 0) return "";
        String text = all.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                if (localName.equals(name)) return child;
            }
        }
        return null;
    }

    static void setXmlFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // 不同 JAXP 实现支持的特性不同，安全默认值仍由其余配置保证
        }
    }

    /** PDF 内嵌附件：至少保留附件名称，正文内容仍由附件本身管理。 */
    static void appendPdfAttachments(PDDocument doc, StringBuilder sb, BiFunction<String, byte[], List<DocumentParser.ParsedChunk>> parser) {
        try {
            var names = doc.getDocumentCatalog().getNames();
            if (names == null || names.getEmbeddedFiles() == null) return;
            var files = names.getEmbeddedFiles().getNames();
            if (files == null) return;
            int depth = ATTACHMENT_DEPTH.get();
            int count = 0;
            for (var entry : files.entrySet()) {
                if (count >= 5) break;
                String name = entry.getKey();
                sb.append("\n[附件]").append(name).append('\n');
                if (depth >= 1) continue;
                try {
                    var embedded = entry.getValue() == null ? null : entry.getValue().getEmbeddedFile();
                    if (embedded == null || embedded.getSize() > 5 * 1024 * 1024) continue;
                    byte[] data = embedded.toByteArray();
                    ATTACHMENT_DEPTH.set(depth + 1);
                    try {
                        String content = parser.apply(name, data).stream()
                                .map(DocumentParser.ParsedChunk::content)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        if (!content.isBlank()) {
                            sb.append("[附件正文]").append(name).append('\n');
                            sb.append(content, 0, Math.min(content.length(), 5000)).append('\n');
                        }
                    } finally {
                        ATTACHMENT_DEPTH.set(depth);
                    }
                } catch (Exception e) {
                    log.debug("PDF 附件正文解析跳过 {}：{}", name, e.getMessage());
                }
                count++;
            }
        } catch (Exception e) {
            log.debug("PDF 附件读取失败（忽略）：{}", e.getMessage());
        }
    }
}
