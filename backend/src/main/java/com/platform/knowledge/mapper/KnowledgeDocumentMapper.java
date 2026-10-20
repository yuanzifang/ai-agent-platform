package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /** 知识库列表的文档数/分段数（租户条件由 TenantLineInnerInterceptor 追加）。 */
    @Select("""
            SELECT knowledge_base_id AS kbId,
                   COUNT(*) AS documentCount,
                   COALESCE(SUM(chunk_count), 0) AS chunkCount
            FROM knowledge_document
            GROUP BY knowledge_base_id
            """)
    List<Map<String, Object>> statsByKb();

    /** 租户文档存储占用（配额校验用）。 */
    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM knowledge_document WHERE tenant_id = #{tenantId}")
    long sumSizeByTenant(@Param("tenantId") Long tenantId);
}
