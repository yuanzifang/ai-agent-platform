package com.platform.knowledge.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 关键字/全文检索抽象：MySQL 全文或 Elasticsearch。 */
public interface KeywordSearchStore {

    String provider();

    List<Map<String, Object>> search(Long tenantId, Long knowledgeBaseId, String query, int limit,
                                     Long userId, boolean tenantWide,
                                     Instant effectiveAfter, Instant effectiveBefore);
}
