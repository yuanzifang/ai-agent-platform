package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** 知识服务内轻量 JSON 读写，失败时回退原值/空值，避免异常影响主流程。 */
public final class KnowledgeJsonSupport {

    private KnowledgeJsonSupport() {
    }

    public static Object parse(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    public static String write(ObjectMapper mapper, Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
