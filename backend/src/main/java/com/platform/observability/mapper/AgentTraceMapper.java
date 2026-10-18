package com.platform.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.observability.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Trace 与聚合查询（需求 3.3/3.4、架构 8.3）。
 * 说明：多租户条件由 TenantLineInnerInterceptor 自动追加（tenant_id = 当前租户）。
 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {

    /** 按天聚合的可加性指标（监控统计的当日/缺失日回落明细口径）。 */
    @Select("""
            <script>
            SELECT DATE(t.created_at) AS statDate,
                   COUNT(*) AS calls,
                   COALESCE(SUM(t.total_tokens), 0) AS tokens,
                   COALESCE(SUM(t.cost), 0) AS cost,
                   SUM(CASE WHEN t.status = 'ERROR' THEN 1 ELSE 0 END) AS errorCount
            FROM agent_trace t
            WHERE t.created_at <![CDATA[>=]]> #{startTime} AND t.created_at <![CDATA[<]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            GROUP BY DATE(t.created_at)
            </script>
            """)
    List<Map<String, Object>> dailyAgg(@Param("agentId") Long agentId,
                                       @Param("startTime") LocalDateTime startTime,
                                       @Param("endTime") LocalDateTime endTime);

    /** 期间去重指标：对话数（DISTINCT session_id）与活跃用户数（DISTINCT user_id）。 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT t.session_id) AS sessions,
                   COUNT(DISTINCT CASE WHEN u.is_service_account = 1 THEN NULL ELSE t.user_id END) AS users,
                   COUNT(*) AS calls
            FROM agent_trace t
            LEFT JOIN sys_user u ON u.id = t.user_id
            WHERE t.created_at <![CDATA[>=]]> #{startTime} AND t.created_at <![CDATA[<]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            </script>
            """)
    Map<String, Object> distinctAgg(@Param("agentId") Long agentId,
                                    @Param("startTime") LocalDateTime startTime,
                                    @Param("endTime") LocalDateTime endTime);

    /** 按天去重活跃用户（日均活跃用户 = 各天去重后取平均）。 */
    @Select("""
            <script>
            SELECT DATE(t.created_at) AS statDate,
                   COUNT(DISTINCT CASE WHEN u.is_service_account = 1 THEN NULL ELSE t.user_id END) AS users
            FROM agent_trace t
            LEFT JOIN sys_user u ON u.id = t.user_id
            WHERE t.created_at <![CDATA[>=]]> #{startTime} AND t.created_at <![CDATA[<]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            GROUP BY DATE(t.created_at)
            </script>
            """)
    List<Map<String, Object>> dailyDistinctUsers(@Param("agentId") Long agentId,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);

    /** 延迟明细（P50/P99 在应用层计算，MySQL 无 PERCENTILE 函数）。 */
    @Select("""
            <script>
            SELECT t.latency_ms AS latencyMs
            FROM agent_trace t
            WHERE t.created_at <![CDATA[>=]]> #{startTime} AND t.created_at <![CDATA[<]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            </script>
            """)
    List<Long> latencies(@Param("agentId") Long agentId,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime);

    /** 监控统计趋势：按时间桶的调用量/活跃用户/错误数（需求 3.3 趋势图）。 */
    @Select("""
            <script>
            SELECT DATE_FORMAT(t.created_at, #{fmt}) AS bucket,
                   COUNT(*) AS calls,
                   COUNT(DISTINCT CASE WHEN u.is_service_account = 1 THEN NULL ELSE t.user_id END) AS activeUsers,
                   SUM(CASE WHEN t.status = 'ERROR' THEN 1 ELSE 0 END) AS errorCount
            FROM agent_trace t
            LEFT JOIN sys_user u ON u.id = t.user_id
            WHERE t.created_at <![CDATA[>=]]> #{startTime} AND t.created_at <![CDATA[<]]> #{endTime}
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
            GROUP BY bucket
            </script>
            """)
    List<Map<String, Object>> trendByBucket(@Param("agentId") Long agentId,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime,
                                            @Param("fmt") String fmt);

    // ---------- Sessions / Users / Versions Tab（需求 3.4.3(6)） ----------

    @Select("""
            <script>
            SELECT t.session_id AS sessionId, COUNT(*) AS traceCount,
                   COALESCE(SUM(t.total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(t.cost), 0) AS cost,
                   MIN(t.created_at) AS firstTime, MAX(t.created_at) AS lastTime
            FROM agent_trace t
            WHERE t.session_id IS NOT NULL
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
              <if test="startTime != null">AND t.created_at <![CDATA[>=]]> #{startTime}</if>
              <if test="endTime != null">AND t.created_at <![CDATA[<=]]> #{endTime}</if>
              <if test="kw != null">AND t.session_id LIKE CONCAT('%', #{kw}, '%')</if>
            GROUP BY t.session_id
            ORDER BY MAX(t.created_at) DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<Map<String, Object>> sessionAgg(@Param("agentId") Long agentId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("kw") String kw,
                                         @Param("offset") long offset, @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(DISTINCT t.session_id) FROM agent_trace t
            WHERE t.session_id IS NOT NULL
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
              <if test="startTime != null">AND t.created_at <![CDATA[>=]]> #{startTime}</if>
              <if test="endTime != null">AND t.created_at <![CDATA[<=]]> #{endTime}</if>
              <if test="kw != null">AND t.session_id LIKE CONCAT('%', #{kw}, '%')</if>
            </script>
            """)
    long sessionAggCount(@Param("agentId") Long agentId,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime, @Param("kw") String kw);

    @Select("""
            <script>
            SELECT t.user_id AS userId, u.display_name AS username,
                   COUNT(*) AS calls,
                   COALESCE(SUM(t.total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(t.cost), 0) AS cost,
                   MIN(t.created_at) AS firstTime, MAX(t.created_at) AS lastTime
            FROM agent_trace t
            LEFT JOIN sys_user u ON u.id = t.user_id
            WHERE t.user_id IS NOT NULL AND (u.is_service_account = 0)
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
              <if test="startTime != null">AND t.created_at <![CDATA[>=]]> #{startTime}</if>
              <if test="endTime != null">AND t.created_at <![CDATA[<=]]> #{endTime}</if>
              <if test="kw != null">AND (u.display_name LIKE CONCAT('%', #{kw}, '%') OR t.user_id = #{kwUserId})</if>
            GROUP BY t.user_id, u.display_name
            ORDER BY MAX(t.created_at) DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<Map<String, Object>> userAgg(@Param("agentId") Long agentId,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime,
                                      @Param("kw") String kw, @Param("kwUserId") Long kwUserId,
                                      @Param("offset") long offset, @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(DISTINCT t.user_id) FROM agent_trace t
            LEFT JOIN sys_user u ON u.id = t.user_id
            WHERE t.user_id IS NOT NULL AND (u.is_service_account = 0)
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
              <if test="startTime != null">AND t.created_at <![CDATA[>=]]> #{startTime}</if>
              <if test="endTime != null">AND t.created_at <![CDATA[<=]]> #{endTime}</if>
              <if test="kw != null">AND (u.display_name LIKE CONCAT('%', #{kw}, '%') OR t.user_id = #{kwUserId})</if>
            </script>
            """)
    long userAggCount(@Param("agentId") Long agentId,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime,
                      @Param("kw") String kw, @Param("kwUserId") Long kwUserId);

    @Select("""
            <script>
            SELECT t.version AS version, COUNT(*) AS calls,
                   SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
                   AVG(t.score) AS avgScore,
                   COALESCE(SUM(t.total_tokens), 0) AS totalTokens,
                   COALESCE(SUM(t.cost), 0) AS cost
            FROM agent_trace t
            WHERE t.version IS NOT NULL
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
              <if test="startTime != null">AND t.created_at <![CDATA[>=]]> #{startTime}</if>
              <if test="endTime != null">AND t.created_at <![CDATA[<=]]> #{endTime}</if>
              <if test="kw != null">AND t.version LIKE CONCAT('%', #{kw}, '%')</if>
            GROUP BY t.version
            ORDER BY MAX(t.created_at) DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<Map<String, Object>> versionAgg(@Param("agentId") Long agentId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("kw") String kw,
                                         @Param("offset") long offset, @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(DISTINCT t.version) FROM agent_trace t
            WHERE t.version IS NOT NULL
              <if test="agentId != null">AND t.agent_id = #{agentId}</if>
              <if test="startTime != null">AND t.created_at <![CDATA[>=]]> #{startTime}</if>
              <if test="endTime != null">AND t.created_at <![CDATA[<=]]> #{endTime}</if>
              <if test="kw != null">AND t.version LIKE CONCAT('%', #{kw}, '%')</if>
            </script>
            """)
    long versionAggCount(@Param("agentId") Long agentId,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("endTime") LocalDateTime endTime, @Param("kw") String kw);

    // ---------- 日汇总任务（需求 6.13） ----------

    /** 会话首日归属口径：统计「最早出现在 statDate 当天」的会话数。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM (
                SELECT t.session_id, MIN(t.created_at) AS firstAt
                FROM agent_trace t
                WHERE t.session_id IS NOT NULL
                  <if test="agentId != null">AND t.agent_id = #{agentId}</if>
                GROUP BY t.session_id
            ) s WHERE DATE(s.firstAt) = #{statDate}
            </script>
            """)
    long firstDaySessions(@Param("agentId") Long agentId, @Param("statDate") java.time.LocalDate statDate);
}
