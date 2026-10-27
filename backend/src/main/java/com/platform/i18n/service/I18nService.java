package com.platform.i18n.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.i18n.entity.I18nMessage;
import com.platform.i18n.mapper.I18nMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 国际化字段映射（需求：前端 i18n 字段从后端获取）。
 *
 * <p>模型：前端静态语言包打底，本服务管理的 DB 条目作为<strong>覆盖/扩展层</strong>——
 * GET messages/{lang} 返回扁平 {key: value}，前端 mergeLocaleMessage 后覆盖静态值；
 * 新语言（如 ja-JP）无需发版，入库即在 locales 列表出现。</p>
 *
 * <p>缓存：进程内按 lang 缓存，任何写操作失效；多实例部署时 TTL 兜底（1 分钟刷新）。
 * 支持格式：JSON（{"key":"value"} 或 [{"key","value","description"}]）与 CSV（key,value,description 带表头，RFC 4180 引号）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nService {

    private static final Pattern LANG_PATTERN = Pattern.compile("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,255}$");
    private static final long CACHE_TTL_MS = 60_000L;

    private final I18nMessageMapper mapper;
    private final ObjectMapper objectMapper;

    /** lang → (key → content)；写入即失效，TTL 兜底多实例一致性。 */
    private final Map<String, CachedMessages> cache = new ConcurrentHashMap<>();

    private record CachedMessages(Map<String, String> messages, long loadedAt) {
    }

    /** 某语言的扁平键值映射（供前端 mergeLocaleMessage）。 */
    public Map<String, String> messages(String lang) {
        String l = normalizeLang(lang);
        CachedMessages cached = cache.get(l);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt() < CACHE_TTL_MS) {
            return cached.messages();
        }
        Map<String, String> messages = new LinkedHashMap<>();
        for (I18nMessage m : mapper.selectList(new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getLang, l))) {
            messages.put(m.getMessageKey(), m.getContent());
        }
        cache.put(l, new CachedMessages(messages, System.currentTimeMillis()));
        return messages;
    }

    /** 数据库中已配置的语言清单（供前端语言选择器扩展）。 */
    public List<String> locales() {
        return mapper.selectList(new LambdaQueryWrapper<I18nMessage>()
                        .select(I18nMessage::getLang)
                        .groupBy(I18nMessage::getLang))
                .stream().map(I18nMessage::getLang).distinct().sorted().toList();
    }

    /** 管理端分页列表：lang 可选、keyword 模糊匹配 key/content/description。 */
    public Page<I18nMessage> page(String lang, String keyword, long page, long size) {
        LambdaQueryWrapper<I18nMessage> wrapper = new LambdaQueryWrapper<I18nMessage>()
                .orderByAsc(I18nMessage::getLang)
                .orderByAsc(I18nMessage::getMessageKey);
        if (lang != null && !lang.isBlank()) wrapper.eq(I18nMessage::getLang, normalizeLang(lang));
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(I18nMessage::getMessageKey, keyword)
                    .or().like(I18nMessage::getContent, keyword)
                    .or().like(I18nMessage::getDescription, keyword));
        }
        return mapper.selectPage(new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200)), wrapper);
    }

    public void upsert(String lang, String messageKey, String content, String description, Long updatedBy) {
        String l = normalizeLang(lang);
        String key = normalizeKey(messageKey);
        if (content == null || content.isBlank()) throw new BusinessException(400, "译文不能为空");
        I18nMessage existing = mapper.selectOne(new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getLang, l)
                .eq(I18nMessage::getMessageKey, key)
                .last("LIMIT 1"));
        if (existing == null) {
            I18nMessage m = new I18nMessage();
            m.setLang(l);
            m.setMessageKey(key);
            m.setContent(content);
            m.setDescription(emptyToNull(description));
            m.setUpdatedBy(updatedBy);
            m.setUpdatedAt(LocalDateTime.now());
            mapper.insert(m);
        } else {
            existing.setContent(content);
            existing.setDescription(emptyToNull(description));
            existing.setUpdatedBy(updatedBy);
            mapper.updateById(existing);
        }
        cache.remove(l);
    }

    public void delete(Long id) {
        I18nMessage m = mapper.selectById(id);
        if (m == null) throw new BusinessException(404, "条目不存在");
        mapper.deleteById(id);
        cache.remove(m.getLang());
    }

    /** 导入：lang 下的 JSON 或 CSV 文本；返回 {inserted, updated, skipped}。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importMessages(String lang, String format, String content, Long updatedBy) {
        String l = normalizeLang(lang);
        List<I18nMessage> rows = "csv".equalsIgnoreCase(format)
                ? parseCsv(l, content)
                : parseJson(l, content);
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (I18nMessage row : rows) {
            try {
                long before = mapper.selectCount(new LambdaQueryWrapper<I18nMessage>()
                        .eq(I18nMessage::getLang, row.getLang())
                        .eq(I18nMessage::getMessageKey, row.getMessageKey()));
                if (before > 0) {
                    updated++;
                } else {
                    inserted++;
                }
                upsert(row.getLang(), row.getMessageKey(), row.getContent(),
                        row.getDescription(), updatedBy);
            } catch (BusinessException e) {
                skipped++;
            }
        }
        cache.remove(l);
        return Map.of("total", rows.size(), "inserted", inserted, "updated", updated, "skipped", skipped);
    }

    /** 导出：CSV（UTF-8 BOM，Excel 可直接打开）或 JSON 扁平键值。 */
    public byte[] export(String lang, String format) {
        String l = normalizeLang(lang);
        Map<String, String> messages = messages(l);
        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder("\uFEFFkey,value,description\n");
            for (Map.Entry<String, String> entry : messages.entrySet()) {
                String description = descriptionOf(l, entry.getKey());
                sb.append(csvField(entry.getKey())).append(',')
                        .append(csvField(entry.getValue())).append(',')
                        .append(csvField(description == null ? "" : description)).append('\n');
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(messages);
        } catch (Exception e) {
            throw new BusinessException(500, "导出失败：" + e.getMessage());
        }
    }

    private String descriptionOf(String lang, String key) {
        I18nMessage m = mapper.selectOne(new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getLang, lang)
                .eq(I18nMessage::getMessageKey, key)
                .last("LIMIT 1"));
        return m == null ? null : m.getDescription();
    }

    /** JSON 导入：{"key":"value"} 或 [{"key","value","description"}]。 */
    private List<I18nMessage> parseJson(String lang, String content) {
        List<I18nMessage> rows = new ArrayList<>();
        try {
            Object root = objectMapper.readValue(content, Object.class);
            if (root instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rows.add(row(lang, String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue()), null));
                }
            } else if (root instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        rows.add(row(lang, str(m.get("key")), str(m.get("value")), str(m.get("description"))));
                    }
                }
            } else {
                throw new BusinessException(400, "JSON 格式不支持");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 解析失败：" + e.getMessage());
        }
        if (rows.isEmpty()) throw new BusinessException(400, "导入内容为空");
        return rows;
    }

    /** CSV 导入：首行表头 key,value,description；RFC 4180 双引号转义。 */
    private List<I18nMessage> parseCsv(String lang, String content) {
        List<String[]> records = parseCsvRecords(content);
        if (records.size() < 2) throw new BusinessException(400, "CSV 缺少数据行");
        List<I18nMessage> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            String[] r = records.get(i);
            if (r.length == 0 || (r.length == 1 && r[0].isBlank())) continue;
            rows.add(row(lang, r[0], r.length > 1 ? r[1] : "", r.length > 2 ? r[2] : null));
        }
        if (rows.isEmpty()) throw new BusinessException(400, "导入内容为空");
        return rows;
    }

    /** 最小 RFC 4180 解析：双引号包裹可含逗号/换行，"" 转义引号。 */
    static List<String[]> parseCsvRecords(String content) {
        List<String[]> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
                fieldStarted = true;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                if (field.length() > 0 || fieldStarted || !current.isEmpty()) {
                    current.add(field.toString());
                    field.setLength(0);
                    rows.add(current.toArray(new String[0]));
                    current = new ArrayList<>();
                    fieldStarted = false;
                }
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || fieldStarted || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current.toArray(new String[0]));
        }
        return rows;
    }

    private I18nMessage row(String lang, String key, String value, String description) {
        I18nMessage m = new I18nMessage();
        m.setLang(lang);
        m.setMessageKey(key);
        m.setContent(value);
        m.setDescription(emptyToNull(description));
        return m;
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) throw new BusinessException(400, "lang 不能为空");
        String l = lang.trim();
        if (!LANG_PATTERN.matcher(l).matches()) throw new BusinessException(400, "lang 格式非法：" + lang);
        return l.toLowerCase(Locale.ROOT).equals("zh") ? "zh-CN"
                : l.toLowerCase(Locale.ROOT).equals("en") ? "en-US" : l;
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) throw new BusinessException(400, "key 不能为空");
        String k = key.trim();
        if (!KEY_PATTERN.matcher(k).matches()) throw new BusinessException(400, "key 仅限字母数字._:-");
        return k;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String csvField(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
