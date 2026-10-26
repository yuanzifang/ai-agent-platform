package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 混合检索关键字路：MySQL ngram 全文索引。 */
    @Select("""
            SELECT id, document_id, chunk_index, parent_index, content, page_from, page_to,
                   MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{kbId}
              AND MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchFulltext(@Param("kbId") Long kbId,
                                             @Param("query") String query,
                                             @Param("limit") int limit);

    /** ACL 版本：userId/tenantWide 下推；旧数据 allowed_users 为空时按知识库级权限兜底。 */
    @Select("""
            <script>
            SELECT id, document_id, chunk_index, parent_index, content, page_from, page_to,
                   MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{kbId}
              AND MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
              <if test="userId != null">
                AND (allowed_users IS NULL
                     OR (tenant_visible = 1 AND #{tenantWide} = TRUE)
                     OR JSON_CONTAINS(allowed_users, JSON_QUOTE(#{userId})))
              </if>
            ORDER BY score DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchFulltextAcl(@Param("kbId") Long kbId,
                                                @Param("query") String query,
                                                @Param("limit") int limit,
                                                @Param("userId") Long userId,
                                                @Param("tenantWide") boolean tenantWide);

    /** 有效期感知的全文检索：JOIN 文档，按内容有效期区间重叠过滤。 */
    @Select("""
            <script>
            SELECT kc.id, kc.document_id, kc.chunk_index, kc.parent_index, kc.content, kc.page_from, kc.page_to,
                   kd.effective_from AS effective_from, kd.effective_to AS effective_to,
                   MATCH(kc.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM knowledge_chunk kc
            JOIN knowledge_document kd ON kd.id = kc.document_id
            WHERE kc.knowledge_base_id = #{kbId}
              AND MATCH(kc.content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
              <if test="effectiveAfter != null">
                AND (kd.effective_to IS NULL OR kd.effective_to &gt;= #{effectiveAfter})
              </if>
              <if test="effectiveBefore != null">
                AND COALESCE(kd.effective_from, kd.created_at) &lt;= #{effectiveBefore}
              </if>
              <if test="userId != null">
                AND (kc.allowed_users IS NULL
                     OR (kc.tenant_visible = 1 AND #{tenantWide} = TRUE)
                     OR JSON_CONTAINS(kc.allowed_users, JSON_QUOTE(#{userId})))
              </if>
            ORDER BY score DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchFulltextByValidity(@Param("kbId") Long kbId,
                                                       @Param("query") String query,
                                                       @Param("limit") int limit,
                                                       @Param("effectiveAfter") LocalDateTime effectiveAfter,
                                                       @Param("effectiveBefore") LocalDateTime effectiveBefore,
                                                       @Param("userId") Long userId,
                                                       @Param("tenantWide") boolean tenantWide);

    /** 有效期感知的 LIKE 兜底。 */
    @Select("""
            <script>
            SELECT kc.id, kc.document_id, kc.chunk_index, kc.parent_index, kc.content, kc.page_from, kc.page_to,
                   kd.effective_from AS effective_from, kd.effective_to AS effective_to, 0.1 AS score
            FROM knowledge_chunk kc
            JOIN knowledge_document kd ON kd.id = kc.document_id
            WHERE kc.knowledge_base_id = #{kbId}
              AND kc.content LIKE CONCAT('%', #{query}, '%')
              <if test="effectiveAfter != null">
                AND (kd.effective_to IS NULL OR kd.effective_to &gt;= #{effectiveAfter})
              </if>
              <if test="effectiveBefore != null">
                AND COALESCE(kd.effective_from, kd.created_at) &lt;= #{effectiveBefore}
              </if>
              <if test="userId != null">
                AND (kc.allowed_users IS NULL
                     OR (kc.tenant_visible = 1 AND #{tenantWide} = TRUE)
                     OR JSON_CONTAINS(kc.allowed_users, JSON_QUOTE(#{userId})))
              </if>
            ORDER BY kc.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchLikeByValidity(@Param("kbId") Long kbId,
                                                   @Param("query") String query,
                                                   @Param("limit") int limit,
                                                   @Param("effectiveAfter") LocalDateTime effectiveAfter,
                                                   @Param("effectiveBefore") LocalDateTime effectiveBefore,
                                                   @Param("userId") Long userId,
                                                   @Param("tenantWide") boolean tenantWide);

    /** 全文索引不可用/短查询兜底。 */
    @Select("""
            SELECT id, document_id, chunk_index, parent_index, content, page_from, page_to, 0.1 AS score
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{kbId}
              AND content LIKE CONCAT('%', #{query}, '%')
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchLike(@Param("kbId") Long kbId,
                                         @Param("query") String query,
                                         @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id, document_id, chunk_index, parent_index, content, page_from, page_to, 0.1 AS score
            FROM knowledge_chunk
            WHERE knowledge_base_id = #{kbId}
              AND content LIKE CONCAT('%', #{query}, '%')
              <if test="userId != null">
                AND (allowed_users IS NULL
                     OR (tenant_visible = 1 AND #{tenantWide} = TRUE)
                     OR JSON_CONTAINS(allowed_users, JSON_QUOTE(#{userId})))
              </if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchLikeAcl(@Param("kbId") Long kbId,
                                            @Param("query") String query,
                                            @Param("limit") int limit,
                                            @Param("userId") Long userId,
                                            @Param("tenantWide") boolean tenantWide);
}
