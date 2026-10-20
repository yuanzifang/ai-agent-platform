package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentSchedule;
import com.platform.agent.entity.AgentScheduleResult;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentScheduleMapper;
import com.platform.agent.mapper.AgentScheduleResultMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.service.ChatStreamService;
import com.platform.observability.mapper.AgentSpanMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 定时调度（需求 3.2(5) / P1）：Cron 任务 CRUD、到期扫描、系统身份调用、结果存储/Webhook。
 * 调度 Trace：kind=internal + tags=scheduled；billing_usage.source=scheduled；系统账号 is_service_account=1。
 * 系统身份不绕过知识库权限（viewer 使用系统账号身份，PARTIAL 未授权库仍跳过）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScheduleService {

    private static final ExecutorService RUN_POOL = Executors.newFixedThreadPool(3);

    private final AgentScheduleMapper scheduleMapper;
    private final AgentScheduleResultMapper resultMapper;
    private final AgentRegistryMapper agentMapper;
    private final ConversationMapper conversationMapper;
    private final SysUserMapper userMapper;
    private final ChatStreamService chatStreamService;
    private final AgentSpanMapper spanMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // ---------- CRUD ----------

    public Map<String, Object> list(Long agentId) {
        agent(agentId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentSchedule s : scheduleMapper.selectList(new LambdaQueryWrapper<AgentSchedule>()
                .eq(AgentSchedule::getAgentId, agentId)
                .orderByDesc(AgentSchedule::getId))) {
            rows.add(view(s));
        }
        return Map.of("list", rows, "total", rows.size());
    }

    @Transactional
    public Map<String, Object> create(Long agentId, Map<String, Object> req, JwtPrincipal principal) {
        agent(agentId);
        AgentSchedule s = new AgentSchedule();
        s.setAgentId(agentId);
        apply(s, req);
        s.setTenantId(principal.tenantId());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        s.setNextRunAt("ENABLED".equals(s.getStatus()) ? next(s.getCronExpr(), LocalDateTime.now()) : null);
        scheduleMapper.insert(s);
        auditService.record(principal.userId(), "agent.schedule.create", "agent",
                String.valueOf(agentId), "SUCCESS",
                Map.of("scheduleId", s.getId(), "cron", s.getCronExpr()));
        return Map.of("id", s.getId(), "nextRunAt", s.getNextRunAt());
    }

    @Transactional
    public void update(Long agentId, Long id, Map<String, Object> req, JwtPrincipal principal) {
        AgentSchedule s = schedule(agentId, id);
        apply(s, req);
        s.setNextRunAt("ENABLED".equals(s.getStatus()) ? next(s.getCronExpr(), LocalDateTime.now()) : null);
        s.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.updateById(s);
        auditService.record(principal.userId(), "agent.schedule.update", "agent",
                String.valueOf(agentId), "SUCCESS", Map.of("scheduleId", id, "status", s.getStatus()));
    }

    @Transactional
    public void delete(Long agentId, Long id, JwtPrincipal principal) {
        AgentSchedule s = schedule(agentId, id);
        resultMapper.delete(new LambdaQueryWrapper<AgentScheduleResult>()
                .eq(AgentScheduleResult::getScheduleId, id));
        scheduleMapper.deleteById(id);
        auditService.record(principal.userId(), "agent.schedule.delete", "agent",
                String.valueOf(agentId), "SUCCESS", Map.of("scheduleId", id, "name", s.getName()));
    }

    public List<AgentScheduleResult> results(Long agentId, Long id) {
        schedule(agentId, id);
        return resultMapper.selectList(new LambdaQueryWrapper<AgentScheduleResult>()
                .eq(AgentScheduleResult::getScheduleId, id)
                .orderByDesc(AgentScheduleResult::getId)
                .last("LIMIT 50"));
    }

    /** 立即执行一次（管理页调试；不影响下次执行时间）。 */
    public Map<String, Object> runNow(Long agentId, Long id) {
        AgentSchedule s = schedule(agentId, id);
        Map<String, Object> result = execute(s);
        AgentSchedule patch = new AgentSchedule();
        patch.setId(s.getId());
        patch.setLastRunAt(LocalDateTime.now());
        patch.setLastStatus(String.valueOf(result.get("status")));
        patch.setLastMessage(truncate(String.valueOf(result.getOrDefault("message", "")), 1000));
        scheduleMapper.updateById(patch);
        return result;
    }

    // ---------- 调度器 ----------

    @Scheduled(fixedDelayString = "${agent.schedule.scan-ms:15000}")
    public void scan() {
        List<AgentSchedule> due;
        try {
            due = scheduleMapper.selectDue(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("定时调度扫描失败：{}", e.getMessage());
            return;
        }
        for (AgentSchedule s : due) {
            String lockKey = "schedule:run:" + s.getId();
            boolean locked = false;
            try {
                locked = Boolean.TRUE.equals(redis.opsForValue()
                        .setIfAbsent(lockKey, UUID.randomUUID().toString(), 5, TimeUnit.MINUTES));
            } catch (Exception e) {
                log.debug("调度锁不可用（继续执行）：{}", e.getMessage());
                locked = true;
            }
            if (!locked) continue;
            try {
                TenantContext.run(s.getTenantId(), () -> {
                    Map<String, Object> result = execute(s);
                    AgentSchedule patch = new AgentSchedule();
                    patch.setId(s.getId());
                    patch.setLastRunAt(LocalDateTime.now());
                    patch.setLastStatus(String.valueOf(result.get("status")));
                    patch.setLastMessage(truncate(String.valueOf(result.getOrDefault("message", "")), 1000));
                    patch.setNextRunAt("ENABLED".equals(s.getStatus())
                            ? next(s.getCronExpr(), LocalDateTime.now()) : null);
                    scheduleMapper.updateById(patch);
                });
            } catch (Exception e) {
                log.warn("定时任务执行异常 schedule={}：{}", s.getId(), e.getMessage());
            } finally {
                try {
                    redis.delete(lockKey);
                } catch (Exception e) {     log.warn("定时任务锁释放/结果落库失败：{}", e.getMessage()); }
            }
        }
    }

    private Map<String, Object> execute(AgentSchedule schedule) {
        Long tenantId = schedule.getTenantId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduleId", schedule.getId());
        try {
            AgentRegistry agent = agentMapper.selectById(schedule.getAgentId());
            if (agent == null || !"PUBLISHED".equals(agent.getStatus())) {
                return fail(result, "Agent 未发布或不存在");
            }
            SysUser system = systemUser(tenantId);
            Conversation conv = new Conversation();
            conv.setSessionId(UUID.randomUUID().toString().replace("-", ""));
            conv.setAgentId(agent.getId());
            conv.setUserId(system.getId());
            conv.setTitle("定时任务：" + schedule.getName());
            conv.setSource("scheduled");
            conv.setStatus("ACTIVE");
            conv.setTenantId(tenantId);
            conv.setLastActiveAt(LocalDateTime.now());
            conv.setCreatedAt(LocalDateTime.now());
            conversationMapper.insert(conv);

            JwtPrincipal principal = new JwtPrincipal(system.getId(), tenantId, false, List.of(), List.of());
            Map<String, Object> params = fromJsonMap(schedule.getInputParams());
            Map<String, Object> variables = new LinkedHashMap<>(params);
            Object customMessage = variables.remove("message");
            String message = customMessage == null || String.valueOf(customMessage).isBlank()
                    ? "定时任务触发：" + schedule.getName() : String.valueOf(customMessage);

            int timeout = schedule.getTimeoutMs() == null ? 60000 : schedule.getTimeoutMs();
            Future<Object> future = RUN_POOL.submit(() -> TenantContext.runWith(tenantId,
                    () -> chatStreamService.sendWithSource(conv.getId(),
                            new ChatStreamService.SendRequest(message, false, variables, null),
                            principal, "scheduled")));
            Object response;
            try {
                response = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                return fail(result, "执行超时（" + timeout + "ms）");
            }
            if (!(response instanceof Map<?, ?> map)) return fail(result, "执行结果异常");
            String answer = map.get("answer") == null ? "" : String.valueOf(map.get("answer"));
            String traceId = map.get("traceId") == null ? null : String.valueOf(map.get("traceId"));
            if (traceId != null) {
                try {
                    spanMapper.markScheduledRoot(traceId);
                } catch (Exception e) {
                    log.debug("调度根 Span 打标失败：{}", e.getMessage());
                }
            }

            AgentScheduleResult row = new AgentScheduleResult();
            row.setScheduleId(schedule.getId());
            row.setTenantId(tenantId);
            row.setTraceId(traceId);
            row.setStatus("SUCCESS");
            row.setAnswer(truncate(answer, 10000));
            row.setCreatedAt(LocalDateTime.now());
            resultMapper.insert(row);

            if ("WEBHOOK".equals(schedule.getNotifyType()) && schedule.getWebhookUrl() != null
                    && !schedule.getWebhookUrl().isBlank()) {
                postWebhook(schedule, row, answer);
            }
            result.put("status", "SUCCESS");
            result.put("traceId", traceId);
            result.put("answer", answer);
            result.put("message", "执行成功");
            auditService.record(system.getId(), "agent.schedule.run", "agent_schedule",
                    String.valueOf(schedule.getId()), "SUCCESS", Map.of("traceId", String.valueOf(traceId)));
            return result;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            return fail(result, root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
        }
    }

    private Map<String, Object> fail(Map<String, Object> result, String error) {
        try {
            AgentScheduleResult row = new AgentScheduleResult();
            row.setScheduleId(((Number) result.get("scheduleId")).longValue());
            row.setTenantId(TenantContext.getTenantId());
            row.setStatus("FAILED");
            row.setError(truncate(error, 1000));
            row.setCreatedAt(LocalDateTime.now());
            resultMapper.insert(row);
        } catch (Exception e) {     log.warn("定时任务锁释放/结果落库失败：{}", e.getMessage()); }
        result.put("status", "FAILED");
        result.put("message", error);
        return result;
    }

    private void postWebhook(AgentSchedule schedule, AgentScheduleResult result, String answer) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scheduleId", schedule.getId());
            payload.put("name", schedule.getName());
            payload.put("agentId", schedule.getAgentId());
            payload.put("status", result.getStatus());
            payload.put("traceId", result.getTraceId());
            payload.put("answer", answer);
            payload.put("executedAt", LocalDateTime.now().toString());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(schedule.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() < 400) {
                        log.info("调度 Webhook 回调 schedule={} http={}", schedule.getId(), resp.statusCode());
                        return;
                    }
                    last = new IllegalStateException("HTTP " + resp.statusCode());
                } catch (Exception e) {
                    last = e;
                }
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("调度 Webhook 回调失败（已重试）schedule={}：{}", schedule.getId(),
                    last == null ? "unknown" : last.getMessage());
        } catch (Exception e) {
            log.warn("调度 Webhook 回调失败 schedule={}：{}", schedule.getId(), e.getMessage());
        }
    }

    private SysUser systemUser(Long tenantId) {
        String username = "system_scheduler_" + tenantId;
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username).last("LIMIT 1"));
        if (user != null) return user;
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        u.setDisplayName("系统调度账号");
        u.setIsPlatformAdmin(0);
        u.setIsServiceAccount(1);
        u.setTenantId(tenantId);
        u.setStatus("ENABLED");
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return u;
    }

    // ---------- helpers ----------

    private void apply(AgentSchedule s, Map<String, Object> req) {
        if (req.get("name") != null) {
            String name = String.valueOf(req.get("name"));
            if (name.isBlank()) throw new BusinessException(400, "任务名称不能为空");
            s.setName(name.trim());
        }
        if (s.getName() == null) throw new BusinessException(400, "任务名称不能为空");
        if (req.get("cronExpr") != null) {
            String cron = normalizeCron(String.valueOf(req.get("cronExpr")));
            s.setCronExpr(cron);
        }
        if (s.getCronExpr() == null) throw new BusinessException(400, "Cron 表达式不能为空");
        next(s.getCronExpr(), LocalDateTime.now()); // 校验
        if (req.containsKey("inputParams")) {
            Object params = req.get("inputParams");
            if (params != null && !(params instanceof Map)) throw new BusinessException(400, "输入参数需为 JSON 对象");
            s.setInputParams(json(params == null ? Map.of() : params));
        }
        if (s.getInputParams() == null) s.setInputParams("{}");
        if (req.containsKey("timeoutMs")) {
            int timeout = toInt(req.get("timeoutMs"), 60000);
            if (timeout < 1000 || timeout > 600000) throw new BusinessException(400, "超时时间需在 1000-600000ms");
            s.setTimeoutMs(timeout);
        }
        if (s.getTimeoutMs() == null) s.setTimeoutMs(60000);
        if (req.get("notifyType") != null) {
            String type = String.valueOf(req.get("notifyType")).toUpperCase();
            if (!List.of("STORE", "WEBHOOK").contains(type)) throw new BusinessException(400, "结果去处仅支持 STORE/WEBHOOK");
            s.setNotifyType(type);
        }
        if (s.getNotifyType() == null) s.setNotifyType("STORE");
        if (req.containsKey("webhookUrl")) {
            String url = req.get("webhookUrl") == null ? null : String.valueOf(req.get("webhookUrl")).trim();
            if (url != null && !url.isBlank()
                    && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw new BusinessException(400, "Webhook 地址仅支持 http/https");
            }
            s.setWebhookUrl(url == null || url.isBlank() ? null : url);
        }
        if ("WEBHOOK".equals(s.getNotifyType()) && (s.getWebhookUrl() == null || s.getWebhookUrl().isBlank())) {
            throw new BusinessException(400, "WEBHOOK 模式需配置回调地址");
        }
        if (req.get("status") != null) {
            String status = String.valueOf(req.get("status")).toUpperCase();
            if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "非法状态");
            s.setStatus(status);
        }
        if (s.getStatus() == null) s.setStatus("ENABLED");
    }

    private String normalizeCron(String raw) {
        String cron = raw == null ? "" : raw.trim();
        if (cron.isEmpty()) throw new BusinessException(400, "Cron 表达式不能为空");
        if (cron.split("\\s+").length == 5) cron = "0 " + cron; // 兼容 5 段（分钟级），补秒位
        return cron;
    }

    private LocalDateTime next(String cron, LocalDateTime from) {
        try {
            LocalDateTime next = CronExpression.parse(cron).next(from);
            if (next == null) throw new BusinessException(400, "Cron 表达式无法计算下次执行时间");
            return next;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "Cron 表达式非法：" + e.getMessage());
        }
    }

    private AgentRegistry agent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        return agent;
    }

    private AgentSchedule schedule(Long agentId, Long id) {
        AgentSchedule s = scheduleMapper.selectById(id);
        if (s == null || !s.getAgentId().equals(agentId)) throw new BusinessException(404, "调度任务不存在");
        return s;
    }

    private Map<String, Object> view(AgentSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("agentId", s.getAgentId());
        m.put("name", s.getName());
        m.put("cronExpr", s.getCronExpr());
        m.put("inputParams", fromJsonMap(s.getInputParams()));
        m.put("timeoutMs", s.getTimeoutMs());
        m.put("notifyType", s.getNotifyType());
        m.put("webhookUrl", s.getWebhookUrl());
        m.put("status", s.getStatus());
        m.put("nextRunAt", s.getNextRunAt());
        m.put("lastRunAt", s.getLastRunAt());
        m.put("lastStatus", s.getLastStatus());
        m.put("lastMessage", s.getLastMessage());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 序列化失败");
        }
    }

    private String truncate(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
