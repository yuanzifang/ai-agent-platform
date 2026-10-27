package com.platform.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.observability.entity.AgentSpan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Span 聚合查询（需求 3.4：Span 结构图 / Top Tools / 模型占比）。
 * 多租户条件由 TenantLineInnerInterceptor 自动追加。
 */
@Mapper
public interface AgentSpanMapper extends BaseMapper<AgentSpan> {

    /** Span 总数 + 按类型计数（agent/chain/llm/tool/retrieval）。 */
    @Select("""
            <script>
            SELECT s.span_type AS spanType, COUNT(*) AS cnt
            FROM agent_span s JOIN agent_trace t ON t.trace_id = s.trace_id
            WHERE s.start_time <![CDATA[>=]]> #{startTime} AND s.start_time <![CDATA[<=]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            GROUP BY s.span_type
            </script>
            """)
    List<Map<String, Object>> countByType(@Param("agentId") Long agentId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /** Span 结构图：按时间桶 × span_type 统计（需求 3.4.3(3)）。 */
    @Select("""
            <script>
            SELECT DATE_FORMAT(s.start_time, #{fmt}) AS bucket, s.span_type AS spanType, COUNT(*) AS cnt
            FROM agent_span s JOIN agent_trace t ON t.trace_id = s.trace_id
            WHERE s.start_time <![CDATA[>=]]> #{startTime} AND s.start_time <![CDATA[<=]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            GROUP BY bucket, s.span_type
            </script>
            """)
    List<Map<String, Object>> seriesByType(@Param("agentId") Long agentId,
                                           @Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime,
                                           @Param("fmt") String fmt);

    /** 模型占比：只统计 LLM Span（需求 3.4.3(5)）。 */
    @Select("""
            <script>
            SELECT COALESCE(s.model_name, s.name) AS modelName, COUNT(*) AS calls,
                   COALESCE(SUM(s.input_tokens + s.output_tokens), 0) AS tokens
            FROM agent_span s JOIN agent_trace t ON t.trace_id = s.trace_id
            WHERE s.span_type = 'llm'
              AND s.start_time <![CDATA[>=]]> #{startTime} AND s.start_time <![CDATA[<=]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            GROUP BY modelName
            ORDER BY calls DESC
            </script>
            """)
    List<Map<String, Object>> modelShare(@Param("agentId") Long agentId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime);

    /** Top Tools：工具调用明细（P95 延迟在应用层计算，需求 3.4.3(4)）。 */
    @Select("""
            <script>
            SELECT COALESCE(s.tool_name, s.name) AS toolName, s.duration_ms AS durationMs
            FROM agent_span s JOIN agent_trace t ON t.trace_id = s.trace_id
            WHERE s.span_type = 'tool'
              AND s.start_time <![CDATA[>=]]> #{startTime} AND s.start_time <![CDATA[<=]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            </script>
            """)
    List<Map<String, Object>> toolCalls(@Param("agentId") Long agentId,
                                        @Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);

    /** 定时调度根 Span 打标（需求 3.2(5)：根 Span attributes 写入 scheduled）。 */
    @Update("""
            UPDATE agent_span
            SET attributes = JSON_SET(COALESCE(attributes, '{}'), '$.tags', JSON_ARRAY('scheduled'))
            WHERE trace_id = #{traceId} AND span_type = 'agent'
            """)
    int markScheduledRoot(@Param("traceId") String traceId);
}
