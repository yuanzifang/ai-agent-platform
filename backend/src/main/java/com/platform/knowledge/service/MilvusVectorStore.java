package com.platform.knowledge.service;

/**
 * Milvus 适配器扩展点。
 *
 * <p>Milvus 客户端依赖和集合/分区管理尚未落地，保留独立接口，后续实现时只需
 * 实现 VectorStore 并注册为 {@code vector.store=milvus} 的 Bean，不需要修改业务层。</p>
 */
public interface MilvusVectorStore extends VectorStore {
}
