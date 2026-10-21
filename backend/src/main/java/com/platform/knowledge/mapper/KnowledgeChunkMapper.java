package com.platform.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.knowledge.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 混合检索关键字路：MySQL ngram 全文索引。 */
    @Select("""
            SELECT id, document_id, chunk_index, content,
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
            SELECT id, document_id, chunk_index, content,
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

    /** 全文索引不可用/短查询兜底。 */
    @Select("""
            SELECT id, document_id, chunk_index, content, 0.1 AS score
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
            SELECT id, document_id, chunk_index, content, 0.1 AS score
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
