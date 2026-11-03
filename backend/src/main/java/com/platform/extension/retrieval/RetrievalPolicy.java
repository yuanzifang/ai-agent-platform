package com.platform.extension.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 检索策略扩展点。
 *
 * <p>核心混合检索只提供通用向量/关键字/融合能力；业务方可通过该接口对查询和
 * 融合后的候选做可选处理。实现应自行判断配置开关，未启用时原样返回。</p>
 */
public interface RetrievalPolicy {

    String id();

    /** 在向量/关键字检索前归一化查询；默认不变。 */
    default String normalizeQuery(String query, Map<String, Object> config) {
        return query;
    }

    /** 在排序、截断前过滤候选；默认不过滤。 */
    default List<Map<String, Object>> filter(String query, Map<String, Object> config,
                                             List<Map<String, Object>> hits) {
        return hits;
    }
}
