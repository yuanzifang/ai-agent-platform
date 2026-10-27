package com.platform.deploy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.agent.service.ApiKeyService;
import com.platform.common.context.TenantContext;
import com.platform.common.security.CryptoService;
import com.platform.common.exception.BusinessException;
import com.platform.deploy.entity.AgentDeployment;
import com.platform.deploy.entity.AgentRuntimeKey;
import com.platform.deploy.entity.AgentDeploymentEvent;
import com.platform.deploy.mapper.AgentDeploymentEventMapper;
import com.platform.deploy.mapper.AgentDeploymentMapper;
import com.platform.deploy.runtime.ContainerRuntime;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 容器部署（MVP：本机 Docker）。
 *
 * 每个已发布的 Agent 部署一个轻量运行时容器（官方 nginx 镜像 + 模板化配置）：
 * 容器对外暴露 OpenAI 兼容的 /v1/chat/completions，内部转发到平台的 /api/v1/open/chat/completions，
 * 并自动注入该 Agent 的集成 API Key，所以调用方不需要再带密钥。
 *
 * 这是「发布 → 运行」闭环的第一版，镜像与端口范围都可配置；后续可替换成自研运行时镜像或 K8s 实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDeploymentService {

    private final AgentDeploymentMapper deploymentMapper;
    private final AgentRuntimeKeyService runtimeKeyService;
    private final CryptoService cryptoService;
    private final AgentDeploymentEventMapper eventMapper;
    private final AgentRegistryMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final ApiKeyService apiKeyService;
    private final AuditService auditService;
    private final ContainerRuntime runtime;

    @Value("${agent.deploy.runtime:gateway}")
    private String deployRuntime;

    @Value("${agent.deploy.image:nginx:alpine}")
    private String nginxImage;

    @Value("${agent.deploy.gateway-image:aap/agent-gateway:1}")
    private String gatewayImage;

    @Value("${agent.deploy.caller-quota-daily:0}")
    private int callerQuotaDaily;

    @Value("${agent.deploy.caller-keys:}")
    private String callerKeys;

    @Value("${agent.deploy.replicas:1}")
    private int defaultReplicas;

    @Value("${agent.deploy.redis-url:}")
    private String redisUrl;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Value("${agent.deploy.port-start:19000}")
    private int portStart;

    @Value("${agent.deploy.port-end:19999}")
    private int portEnd;

    @Value("${agent.deploy.bind:0.0.0.0}")
    private String bind;

    @Value("${agent.deploy.platform-base-url:http://host.docker.internal:8080}")
    private String platformBase;

    @Value("${agent.deploy.public-base:}")
    private String publicBase;

    @Value("${agent.deploy.workdir:}")
    private String workdir;

    @Value("${agent.deploy.rate-limit-per-min:60}")
    private int rateLimitPerMin;

    @Value("${agent.deploy.rate-limit-burst:20}")
    private int rateLimitBurst;

    @Value("${agent.deploy.cors-origins:*}")
    private String corsOrigins;

    private static final String LB_SUFFIX = "-lb";

    private static final String NGINX_TEMPLATE = """
            limit_req_zone $binary_remote_addr zone=agent_gw:10m rate=${RATE_LIMIT}r/m;

            server {
                listen 80;
                server_name _;
                default_type application/json;
                limit_req zone=agent_gw burst=${RATE_BURST} nodelay;
                limit_req_status 429;

                add_header Access-Control-Allow-Origin "${CORS_ORIGIN}" always;
                add_header Access-Control-Allow-Methods "GET, POST, OPTIONS" always;
                add_header Access-Control-Allow-Headers "Authorization, Content-Type, X-Requested-With" always;

                # CORS 预检不需要令牌
                if ($request_method = OPTIONS) {
                    return 204;
                }

                location = /healthz {
                    access_log off;
                    default_type text/plain;
                    return 200 "ok";
                }

                location = /v1/models {
                    if ($http_authorization != "Bearer ${RUNTIME_TOKEN}") {
                        return 401 '{"error":{"message":"invalid or missing bearer token","type":"invalid_request_error"}}';
                    }
                    return 200 '{"object":"list","data":[{"id":"${AGENT_SLUG}","object":"model","owned_by":"ai-agent-platform"}]}';
                }

                location /v1/ {
                    if ($http_authorization != "Bearer ${RUNTIME_TOKEN}") {
                        return 401 '{"error":{"message":"invalid or missing bearer token","type":"invalid_request_error"}}';
                    }
                    rewrite ^/v1/(.*)$ /api/v1/open/$1 break;
                    proxy_http_version 1.1;
                    proxy_set_header Host $host;
                    proxy_set_header X-Api-Key "${API_KEY}";
                    proxy_set_header X-Real-IP $remote_addr;
                    proxy_set_header Connection "";
                    proxy_buffering off;
                    proxy_cache off;
                    proxy_read_timeout 300s;
                    proxy_send_timeout 300s;
                    proxy_pass ${PLATFORM_BASE};
                }
            }
            """;

    /** 部署信息 + Docker 可用性（前端据此展示按钮/警告）。 */
    public Map<String, Object> status(Long agentId) {
        AgentRegistry agent = requireAgent(agentId);
        AgentDeployment deployment = find(agentId);
        if (deployment != null) refreshStatus(deployment);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentId", agentId);
        data.put("agentStatus", agent.getStatus());
        data.put("runtime", gatewayRuntime() ? "gateway" : "nginx");
        data.put("image", gatewayRuntime() ? gatewayImage : nginxImage);
        data.put("dockerAvailable", runtime.available());
        data.put("dockerVersion", runtime.version());
        data.put("deployment", deployment == null ? null : view(deployment));
        return data;
    }

    @Transactional
    public Map<String, Object> deploy(Long agentId, JwtPrincipal principal) {
        return deploy(agentId, null, principal);
    }

    /** 部署/重新部署：按 replicas 创建多个容器（1-5），共享 Redis 中的限流/配额/用量。 */
    @Transactional
    public Map<String, Object> deploy(Long agentId, Integer requestedReplicas, JwtPrincipal principal) {
        AgentRegistry agent = requireAgent(agentId);
        if (!"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(400, "请先发布 Agent，再部署到容器");
        }
        if (!runtime.available()) {
            throw new BusinessException(409,
                    "本机 Docker 不可用：请安装并启动 Docker Desktop（或 colima），确保命令行可执行 docker info");
        }

        AgentDeployment deployment = find(agentId);
        boolean existed = deployment != null;
        if (deployment == null) {
            deployment = new AgentDeployment();
            deployment.setAgentId(agentId);
            deployment.setTenantId(agent.getTenantId());
            deployment.setContainerName("aap-agent-" + agentId);
            deployment.setCreatedAt(LocalDateTime.now());
            deployment.setStatus("PENDING");
            deployment.setReplicaCount(1);
        }
        int replicas = requestedReplicas != null ? requestedReplicas
                : (deployment.getReplicaCount() != null && deployment.getReplicaCount() > 0
                    ? deployment.getReplicaCount() : defaultReplicas);
        replicas = Math.max(1, Math.min(5, replicas));
        if (replicas > 1 && (!gatewayRuntime() || redisUrl == null || redisUrl.isBlank())) {
            throw new BusinessException(400, "多副本部署需要 gateway 运行时并配置 agent.deploy.redis-url（共享限流/配额/用量）");
        }

        Integer previousLbPort = deployment.getLbHostPort();
        removeReplicaContainers(deployment);
        // 旧 Key 先清理，避免每 Agent 10 个 Key 的上限被重复部署占满
        deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);

        String baseName = "aap-agent-" + agentId;
        String slug = "agent-" + agentId;
        String apiKey;
        try {
            Map<String, Object> key = apiKeyService.create(agentId, Map.of(
                    "name", "container:" + baseName,
                    "channel", "api"), principal);
            deployment.setApiKeyId(((Number) key.get("id")).longValue());
            apiKey = String.valueOf(key.get("apiKey"));
        } catch (BusinessException e) {
            deployment.setStatus("FAILED");
            deployment.setLastError("创建集成 API Key 失败：" + e.getMessage());
            save(deployment, existed);
            throw e;
        }

        boolean gateway = gatewayRuntime();
        String effectiveImage = gateway ? gatewayImage : nginxImage;
        int containerPort = gateway ? 8080 : 80;
        deployment.setImage(effectiveImage);
        deployment.setStatus("PENDING");
        deployment.setLastError(null);
        deployment.setDeployedBy(principal.userId());
        deployment.setVersion(latestVersion(agentId));
        deployment.setReplicaCount(replicas);
        save(deployment, existed);

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PLATFORM_BASE", platformBase.replaceAll("/+$", ""));
            env.put("API_KEY", apiKey);
            env.put("AGENT_SLUG", slug);
            env.put("RUNTIME_TOKEN", ensureRuntimeToken(deployment));
            env.put("RATE_BURST", String.valueOf(Math.max(0, rateLimitBurst)));
            env.put("CORS_ORIGIN", corsOrigins == null || corsOrigins.isBlank() ? "*" : corsOrigins.trim());
            if (gateway && redisUrl != null && !redisUrl.isBlank()) env.put("REDIS_URL", redisUrl.trim());
            List<String> mounts;
            if (gateway) {
                env.put("PORT", "8080");
                env.put("RATE_LIMIT_PER_MIN", String.valueOf(Math.max(0, rateLimitPerMin)));
                env.put("CALLER_QUOTA_DAILY", String.valueOf(Math.max(0, callerQuotaDaily)));
                List<Map<String, Object>> callerKeyPayload = new ArrayList<>();
                for (AgentRuntimeKey key : runtimeKeyService.enabledKeys(agentId)) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("hash", key.getKeyHash());
                    payload.put("quota", key.getDailyQuota() == null ? 0 : key.getDailyQuota());
                    payload.put("name", key.getName());
                    callerKeyPayload.add(payload);
                }
                if (!callerKeyPayload.isEmpty()) {
                    env.put("CALLER_KEYS_JSON", JSON.writeValueAsString(callerKeyPayload));
                }
                if (callerKeys != null && !callerKeys.isBlank()) env.put("CALLER_KEYS", callerKeys.trim());
                mounts = List.of();
            } else {
                env.put("RATE_LIMIT", String.valueOf(Math.max(1, rateLimitPerMin)));
                env.put("NGINX_ENTRYPOINT_QUIET_LOGS", "1");
                mounts = List.of(templateDir().toString() + ":/etc/nginx/templates:ro");
            }

            Set<Integer> reserved = new HashSet<>();
            for (int i = 0; i < replicas; i++) {
                String name = baseName + (i == 0 ? "" : "-r" + (i + 1));
                int port = allocatePort(deployment, reserved, i == 0);
                reserved.add(port);
                ContainerRuntime.CreateRequest request = new ContainerRuntime.CreateRequest(
                        name,
                        effectiveImage,
                        bind,
                        port,
                        containerPort,
                        env,
                        Map.of("aap.agent_id", String.valueOf(agentId),
                                "aap.tenant_id", String.valueOf(agent.getTenantId()),
                                "aap.managed_by", "ai-agent-platform"),
                        mounts,
                        List.of());
                String containerId = runtime.create(request);
                runtime.start(containerId);
                waitForHealthy(port);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("containerId", containerId);
                row.put("hostPort", port);
                row.put("status", "RUNNING");
                row.put("endpoint", publicEndpoint(port));
                row.put("localEndpoint", "http://localhost:" + port + "/v1");
                rows.add(row);
            }
            applyReplicaRows(deployment, rows);
            if (replicas > 1) {
                createLoadBalancer(deployment, rows, reserved, previousLbPort);
                deployment.setEndpoint(deployment.getLbEndpoint());
                deployment.setLocalEndpoint(deployment.getLbLocalEndpoint());
            } else {
                removeLoadBalancer(deployment);
            }
            deployment.setStatus("RUNNING");
            save(deployment, true);
            auditService.record(principal.userId(), "agent.deploy", "agent", String.valueOf(agentId),
                    "SUCCESS", Map.of("containers", rows.size(), "port", deployment.getHostPort()));
            recordEvent(deployment, "DEPLOY", "SUCCESS", null, principal);
            return view(deployment);
        } catch (Exception e) {
            removeLoadBalancer(deployment);
            for (Map<String, Object> row : rows) {
                removeContainerQuietly(String.valueOf(row.get("name")));
            }
            if (rows.isEmpty()) removeContainerQuietly(baseName);
            deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);
            deployment.setStatus("FAILED");
            deployment.setLastError(message(e));
            deployment.setReplicasJson(null);
            deployment.setReplicaCount(replicas);
            save(deployment, true);
            auditService.record(principal.userId(), "agent.deploy", "agent", String.valueOf(agentId),
                    "FAILED", Map.of("error", message(e)));
            recordEvent(deployment, "DEPLOY", "FAILED", message(e), principal);
            if (e instanceof BusinessException be) throw be;
            throw new BusinessException(500, "部署失败：" + message(e));
        }
    }

    public Map<String, Object> start(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = requireDeployment(agentId);
        List<Map<String, Object>> rows = replicaRows(deployment);
        for (Map<String, Object> row : rows) {
            runtime.start(String.valueOf(row.get("name")));
            row.put("status", "RUNNING");
            row.remove("error");
        }
        if (deployment.getLbContainerName() != null && !deployment.getLbContainerName().isBlank()) {
            runtime.start(deployment.getLbContainerName());
            deployment.setLbStatus("RUNNING");
        }
        applyReplicaRows(deployment, rows);
        deployment.setStatus("RUNNING");
        deployment.setLastError(null);
        save(deployment, true);
        recordEvent(deployment, "START", "SUCCESS", null, principal);
        return view(deployment);
    }

    public Map<String, Object> stop(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = requireDeployment(agentId);
        if (deployment.getLbContainerName() != null && !deployment.getLbContainerName().isBlank()) {
            runtime.stop(deployment.getLbContainerName());
            deployment.setLbStatus("STOPPED");
        }
        List<Map<String, Object>> rows = replicaRows(deployment);
        for (Map<String, Object> row : rows) {
            runtime.stop(String.valueOf(row.get("name")));
            row.put("status", "STOPPED");
        }
        applyReplicaRows(deployment, rows);
        deployment.setStatus("STOPPED");
        save(deployment, true);
        recordEvent(deployment, "STOP", "SUCCESS", null, principal);
        return view(deployment);
    }

    @Transactional
    public void remove(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = requireDeployment(agentId);
        removeLoadBalancer(deployment);
        for (Map<String, Object> row : replicaRows(deployment)) {
            removeContainerQuietly(String.valueOf(row.get("name")));
        }
        removeContainerQuietly(deployment.getContainerName());
        deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);
        recordEvent(deployment, "REMOVE", "SUCCESS", null, principal);
        deploymentMapper.deleteById(deployment.getId());
        auditService.record(principal.userId(), "agent.undeploy", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("containers", replicaRows(deployment).size()));
    }

    /** 部署/启停/删除历史（按时间倒序）。 */
    public List<Map<String, Object>> history(Long agentId, int limit) {
        requireAgent(agentId);
        int size = Math.min(Math.max(limit, 1), 200);
        List<AgentDeploymentEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentDeploymentEvent>()
                .eq(AgentDeploymentEvent::getAgentId, agentId)
                .orderByDesc(AgentDeploymentEvent::getId)
                .last("LIMIT " + size));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentDeploymentEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("action", e.getAction());
            m.put("status", e.getStatus());
            m.put("version", e.getVersion());
            m.put("containerName", e.getContainerName());
            m.put("hostPort", e.getHostPort());
            m.put("message", e.getMessage());
            m.put("operatorId", e.getOperatorId());
            m.put("createdAt", e.getCreatedAt());
            rows.add(m);
        }
        return rows;
    }

    private void recordEvent(AgentDeployment deployment, String action, String status,
                             String message, JwtPrincipal principal) {
        try {
            AgentDeploymentEvent event = new AgentDeploymentEvent();
            event.setAgentId(deployment.getAgentId());
            event.setDeploymentId(deployment.getId());
            event.setTenantId(deployment.getTenantId());
            event.setAction(action);
            event.setStatus(status);
            event.setVersion(deployment.getVersion());
            event.setContainerName(deployment.getContainerName());
            event.setHostPort(deployment.getHostPort());
            event.setMessage(message == null || message.length() <= 1000 ? message : message.substring(0, 1000));
            event.setOperatorId(principal == null ? null : principal.userId());
            event.setCreatedAt(LocalDateTime.now());
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("写入部署事件失败（忽略）：{}", e.getMessage());
        }
    }

    /** 代理容器 /stats：当日按 Key 的请求数与流式数。 */
    public Map<String, Object> stats(Long agentId) {
        AgentDeployment deployment = requireDeployment(agentId);
        String token = runtimeToken(agentId);
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2)).build();
        int port = replicaRows(deployment).stream()
                .filter(row -> "RUNNING".equals(row.get("status")))
                .map(row -> row.get("hostPort"))
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .findFirst()
                .orElse(deployment.getHostPort());
        java.net.http.HttpRequest request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/stats"))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        try {
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BusinessException(409, "当前运行时不支持用量统计（请使用 gateway 运行时）");
            }
            Map<String, Object> result = JSON.readValue(response.body(), Map.class);
            result.put("runtime", gatewayRuntime() ? "gateway" : "nginx");
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(409, "获取容器用量失败：" + e.getMessage());
        }
    }

    /** 当前部署的访问令牌（明文返回给有编辑权限的调用方，用于 curl）。 */
    public String runtimeToken(Long agentId) {
        AgentDeployment deployment = requireDeployment(agentId);
        String token = deployment.getRuntimeToken() == null ? null : cryptoService.decrypt(deployment.getRuntimeToken());
        if (token == null || token.isBlank()) {
            throw new BusinessException(404, "该部署未启用访问令牌，请重新部署");
        }
        return token;
    }

    /** 轮换令牌：生成新令牌并重建容器使其生效。 */
    @Transactional
    public Map<String, Object> rotateToken(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = requireDeployment(agentId);
        deployment.setRuntimeToken(cryptoService.encrypt(generateRuntimeToken()));
        save(deployment, true);
        return deploy(agentId, principal);
    }

    private String ensureRuntimeToken(AgentDeployment deployment) {
        String token = deployment.getRuntimeToken() == null ? null : cryptoService.decrypt(deployment.getRuntimeToken());
        if (token == null || token.isBlank()) token = generateRuntimeToken();
        deployment.setRuntimeToken(cryptoService.encrypt(token));
        return token;
    }

    private String generateRuntimeToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** Agent 被删除时清理容器与 Key（无部署则静默返回）。 */
    @Transactional
    public void removeIfExists(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = find(agentId);
        if (deployment == null) return;
        removeLoadBalancer(deployment);
        for (Map<String, Object> row : replicaRows(deployment)) {
            removeContainerQuietly(String.valueOf(row.get("name")));
        }
        removeContainerQuietly(deployment.getContainerName());
        deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);
        recordEvent(deployment, "REMOVE", "SUCCESS", "agent deleted", principal);
        deploymentMapper.deleteById(deployment.getId());
    }

    public String logs(Long agentId, int tail) {
        AgentDeployment deployment = requireDeployment(agentId);
        String name = replicaRows(deployment).stream()
                .filter(row -> "RUNNING".equals(row.get("status")))
                .map(row -> String.valueOf(row.get("name")))
                .findFirst()
                .orElse(deployment.getContainerName());
        return runtime.logs(name, Math.min(Math.max(tail, 1), 1000));
    }

    // ------------------------------------------------------------------

    private AgentDeployment find(Long agentId) {
        return deploymentMapper.selectOne(new LambdaQueryWrapper<AgentDeployment>()
                .eq(AgentDeployment::getAgentId, agentId).last("LIMIT 1"));
    }

    private AgentDeployment requireDeployment(Long agentId) {
        AgentDeployment deployment = find(agentId);
        if (deployment == null) throw new BusinessException(404, "该 Agent 尚未部署到容器");
        return deployment;
    }

    private AgentRegistry requireAgent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BusinessException(404, "Agent 不存在或无权访问");
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && agent.getTenantId() != null && !tenantId.equals(agent.getTenantId())) {
            throw new BusinessException(403, "无权操作该 Agent");
        }
        return agent;
    }

    private void save(AgentDeployment deployment, boolean update) {
        deployment.setUpdatedAt(LocalDateTime.now());
        if (update && deployment.getId() != null) {
            deploymentMapper.updateById(deployment);
        } else if (deployment.getId() == null) {
            deploymentMapper.insert(deployment);
        } else {
            deploymentMapper.updateById(deployment);
        }
    }

    private void refreshStatus(AgentDeployment deployment) {
        List<Map<String, Object>> rows = replicaRows(deployment);
        if (rows.isEmpty()) return;
        boolean changed = false;
        boolean anyRunning = false;
        boolean anyFailed = false;
        String firstError = null;
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name"));
            try {
                ContainerRuntime.Info info = runtime.inspect(name);
                String next = info.running() ? "RUNNING" : "STOPPED";
                if (!next.equals(row.get("status"))) {
                    row.put("status", next);
                    changed = true;
                }
                if (info.running()) anyRunning = true;
                if (info.error() != null && !info.error().isBlank()) row.put("error", info.error());
            } catch (BusinessException e) {
                anyFailed = true;
                if (firstError == null) firstError = e.getMessage();
                if (!"FAILED".equals(row.get("status"))) {
                    row.put("status", "FAILED");
                    changed = true;
                }
                row.put("error", e.getMessage());
            }
        }
        boolean lbRunning = true;
        if (deployment.getLbContainerName() != null && !deployment.getLbContainerName().isBlank()) {
            lbRunning = false;
            try {
                ContainerRuntime.Info info = runtime.inspect(deployment.getLbContainerName());
                String next = info.running() ? "RUNNING" : "STOPPED";
                if (!next.equals(deployment.getLbStatus())) {
                    deployment.setLbStatus(next);
                    changed = true;
                }
                lbRunning = info.running();
                if (!lbRunning && firstError == null) firstError = "负载均衡容器未运行";
            } catch (BusinessException e) {
                deployment.setLbStatus("FAILED");
                changed = true;
                anyFailed = true;
                if (firstError == null) firstError = e.getMessage();
            }
        }
        String aggregate;
        if (anyRunning) {
            aggregate = lbRunning ? "RUNNING" : "FAILED";
        } else {
            aggregate = anyFailed ? "FAILED" : "STOPPED";
        }
        boolean errorChanged = firstError != null && !firstError.equals(deployment.getLastError());
        if (changed || !aggregate.equals(deployment.getStatus()) || errorChanged) {
            applyReplicaRows(deployment, rows);
            deployment.setStatus(aggregate);
            if (firstError != null) deployment.setLastError(firstError);
            save(deployment, true);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> replicaRows(AgentDeployment deployment) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (deployment.getReplicasJson() != null && !deployment.getReplicasJson().isBlank()) {
            try {
                List<Map<String, Object>> parsed = JSON.readValue(deployment.getReplicasJson(), List.class);
                if (parsed != null) rows.addAll(parsed);
            } catch (Exception ignored) { /* 历史脏数据按单副本处理 */ }
        }
        if (rows.isEmpty() && deployment.getContainerName() != null && !deployment.getContainerName().isBlank()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", deployment.getContainerName());
            row.put("containerId", deployment.getContainerId());
            row.put("hostPort", deployment.getHostPort());
            row.put("status", deployment.getStatus());
            row.put("endpoint", deployment.getEndpoint());
            row.put("localEndpoint", deployment.getLocalEndpoint());
            rows.add(row);
        }
        return rows;
    }

    private void applyReplicaRows(AgentDeployment deployment, List<Map<String, Object>> rows) {
        try {
            deployment.setReplicasJson(JSON.writeValueAsString(rows));
        } catch (Exception e) {
            throw new BusinessException(500, "副本信息序列化失败");
        }
        deployment.setReplicaCount(rows.size());
        if (!rows.isEmpty()) {
            Map<String, Object> first = rows.get(0);
            deployment.setContainerName(String.valueOf(first.get("name")));
            deployment.setContainerId(first.get("containerId") == null ? null : String.valueOf(first.get("containerId")));
            deployment.setHostPort(first.get("hostPort") instanceof Number n ? n.intValue() : null);
            deployment.setEndpoint(first.get("endpoint") == null ? null : String.valueOf(first.get("endpoint")));
            deployment.setLocalEndpoint(first.get("localEndpoint") == null ? null : String.valueOf(first.get("localEndpoint")));
        }
        // 多副本时对外地址保持统一 LB 入口，不因刷新副本状态退回第一个副本。
        if (deployment.getLbEndpoint() != null) {
            deployment.setEndpoint(deployment.getLbEndpoint());
            deployment.setLocalEndpoint(deployment.getLbLocalEndpoint());
        }
    }

    private void removeReplicaContainers(AgentDeployment deployment) {
        removeLoadBalancer(deployment);
        for (Map<String, Object> row : replicaRows(deployment)) {
            removeContainerQuietly(String.valueOf(row.get("name")));
        }
        if (deployment.getContainerName() != null) {
            removeContainerQuietly(deployment.getContainerName());
        }
        deployment.setReplicasJson(null);
    }

    private int allocatePort(AgentDeployment deployment, Set<Integer> reserved, boolean preferExisting) {
        Set<Integer> used = new HashSet<>();
        for (AgentDeployment d : deploymentMapper.selectList(null)) {
            if (d.getHostPort() != null && !d.getId().equals(deployment.getId())) used.add(d.getHostPort());
        }
        // 优先复用原端口：客户端拿到的地址尽量不因重新部署而变化
        if (preferExisting && deployment.getHostPort() != null
                && !used.contains(deployment.getHostPort()) && !reserved.contains(deployment.getHostPort())) {
            for (int i = 0; i < 15; i++) {
                if (portFree(deployment.getHostPort())) return deployment.getHostPort();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int start = Math.max(1024, portStart);
        int end = Math.max(start, portEnd);
        for (int port = start; port <= end; port++) {
            if (!used.contains(port) && !reserved.contains(port) && portFree(port)) return port;
        }
        throw new BusinessException(409, "端口池已用尽（" + start + "-" + end + "），请调整 agent.deploy.port-start/port-end");
    }

    private boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            // 允许复用 TIME_WAIT 端口，重新部署时尽量保持原端口
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String latestVersion(Long agentId) {
        AgentVersion latest = versionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .orderByDesc(AgentVersion::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getVersion();
    }

    private Path templateDir() {
        try {
            Path dir = runtimeBase().resolve("templates");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("default.conf.template"), NGINX_TEMPLATE, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new BusinessException(500, "写入容器配置模板失败：" + message(e));
        }
    }

    private Path runtimeBase() {
        // 默认放用户目录下：macOS 上 Docker/colima 默认只共享 $HOME，
        // 系统临时目录（/var/folders/...）容器里挂载不到。
        return (workdir == null || workdir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".aap-agent-runtime")
                : Path.of(workdir);
    }

    /** 轮询容器 /healthz，确认配置生效；超时抛 409 并回滚容器。 */
    private void waitForHealthy(int port) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(1)).build();
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/healthz"))
                        .timeout(java.time.Duration.ofSeconds(2)).GET().build();
                if (client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 容器还在启动
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new BusinessException(409, "容器启动后健康检查未通过，请查看部署日志（容器已回滚）");
    }

    private String publicEndpoint(int port) {
        String host = (publicBase == null || publicBase.isBlank()) ? primaryHost() : publicBase.replaceAll("/+$", "");
        return host + ":" + port + "/v1";
    }

    private boolean gatewayRuntime() {
        return !"nginx".equalsIgnoreCase(deployRuntime == null ? "" : deployRuntime.trim());
    }

    /** 选一个外部可访问的地址：优先 Tailscale 100.x，其次内网段，最后 localhost。 */
    private String primaryHost() {
        String best = null;
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.getAddress().length != 4 || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    boolean tailscale = ip.startsWith("100.");
                    boolean privateNet = ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
                    if (tailscale) return "http://" + ip;
                    if (privateNet && best == null) best = "http://" + ip;
                }
            }
        } catch (Exception ignored) {
            // 网络枚举失败时退回 localhost
        }
        return best == null ? "http://localhost" : best;
    }

    /** 多副本统一入口：额外启动一个 nginx 容器，轮询转发到各副本的宿主端口。 */
    private void createLoadBalancer(AgentDeployment deployment, List<Map<String, Object>> rows,
                                    Set<Integer> reserved, Integer previousLbPort) {
        if (rows.size() < 2) return;
        removeLoadBalancer(deployment);
        String name = deployment.getContainerName() + LB_SUFFIX;
        int port = allocateLoadBalancerPort(deployment, reserved, previousLbPort);
        Path configDir = lbConfigDir(name, rows);
        Map<String, String> labels = Map.of(
                "aap.agent_id", String.valueOf(deployment.getAgentId()),
                "aap.tenant_id", String.valueOf(deployment.getTenantId()),
                "aap.managed_by", "ai-agent-platform",
                "aap.role", "load-balancer");
        String containerId = null;
        try {
            ContainerRuntime.CreateRequest request = new ContainerRuntime.CreateRequest(
                    name,
                    nginxImage,
                    bind,
                    port,
                    80,
                    Map.of(),
                    labels,
                    List.of(configDir.resolve("default.conf") + ":/etc/nginx/conf.d/default.conf:ro"),
                    List.of());
            containerId = runtime.create(request);
            runtime.start(containerId);
            waitForHealthy(port);
            deployment.setLbContainerName(name);
            deployment.setLbContainerId(containerId);
            deployment.setLbHostPort(port);
            deployment.setLbStatus("RUNNING");
            deployment.setLbEndpoint(publicEndpoint(port));
            deployment.setLbLocalEndpoint("http://localhost:" + port + "/v1");
        } catch (Exception e) {
            if (containerId != null) removeContainerQuietly(containerId);
            removeContainerQuietly(name);
            clearLoadBalancer(deployment);
            throw new BusinessException(500, "创建多副本负载均衡失败：" + message(e));
        }
    }

    private int allocateLoadBalancerPort(AgentDeployment deployment, Set<Integer> reserved,
                                         Integer previousLbPort) {
        if (previousLbPort != null && !reserved.contains(previousLbPort) && portFree(previousLbPort)) {
            return previousLbPort;
        }
        return allocatePort(deployment, reserved, false);
    }

    private Path lbConfigDir(String name, List<Map<String, Object>> rows) {
        try {
            Path dir = runtimeBase().resolve("lb").resolve(name);
            Files.createDirectories(dir);
            StringBuilder config = new StringBuilder();
            config.append("upstream agent_replicas {\n    least_conn;\n");
            for (Map<String, Object> row : rows) {
                Object raw = row.get("hostPort");
                if (!(raw instanceof Number n)) continue;
                config.append("    server host.docker.internal:").append(n.intValue())
                        .append(" max_fails=2 fail_timeout=10s;\n");
            }
            config.append("""
                    }
                    server {
                        listen 80;
                        client_max_body_size 2m;
                        location = /healthz {
                            proxy_pass http://agent_replicas/healthz;
                            proxy_connect_timeout 2s;
                            proxy_read_timeout 3s;
                        }
                        location / {
                            proxy_pass http://agent_replicas;
                            proxy_http_version 1.1;
                            proxy_set_header Host $host;
                            proxy_set_header X-Real-IP $remote_addr;
                            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                            proxy_set_header X-Forwarded-Proto $scheme;
                            proxy_set_header Connection "";
                            proxy_buffering off;
                            proxy_cache off;
                            proxy_read_timeout 300s;
                            proxy_send_timeout 300s;
                        }
                    }
                    """);
            Files.writeString(dir.resolve("default.conf"), config.toString(), StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new BusinessException(500, "写入负载均衡配置失败：" + message(e));
        }
    }

    private void removeLoadBalancer(AgentDeployment deployment) {
        if (deployment == null) return;
        if (deployment.getLbContainerName() != null && !deployment.getLbContainerName().isBlank()) {
            removeContainerQuietly(deployment.getLbContainerName());
        }
        clearLoadBalancer(deployment);
    }

    private void clearLoadBalancer(AgentDeployment deployment) {
        if (deployment == null) return;
        deployment.setLbContainerName(null);
        deployment.setLbContainerId(null);
        deployment.setLbHostPort(null);
        deployment.setLbStatus(null);
        deployment.setLbEndpoint(null);
        deployment.setLbLocalEndpoint(null);
    }

    private void removeContainerQuietly(String name) {
        if (name == null || name.isBlank()) return;
        try {
            runtime.remove(name);
        } catch (Exception e) {
            log.debug("清理容器失败（可忽略）：{} {}", name, e.getMessage());
        }
    }

    private void deleteKeyQuietly(Long agentId, Long keyId, JwtPrincipal principal) {
        if (keyId == null) return;
        try {
            apiKeyService.delete(agentId, keyId, principal);
        } catch (Exception e) {
            log.debug("清理部署 API Key 失败（可忽略）：{} {}", keyId, e.getMessage());
        }
    }

    private Map<String, Object> view(AgentDeployment deployment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", deployment.getAgentId());
        m.put("version", deployment.getVersion());
        m.put("containerName", deployment.getContainerName());
        m.put("containerId", deployment.getContainerId());
        m.put("image", deployment.getImage());
        m.put("hostPort", deployment.getHostPort());
        m.put("endpoint", deployment.getEndpoint());
        m.put("localEndpoint", deployment.getLocalEndpoint());
        List<Map<String, Object>> replicas = replicaRows(deployment);
        m.put("replicas", replicas);
        m.put("replicaCount", replicas.size());
        m.put("endpoints", replicas.stream()
                .map(row -> row.get("endpoint"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList());
        m.put("lbContainerName", deployment.getLbContainerName());
        m.put("lbHostPort", deployment.getLbHostPort());
        m.put("lbStatus", deployment.getLbStatus());
        m.put("lbEndpoint", deployment.getLbEndpoint());
        m.put("lbLocalEndpoint", deployment.getLbLocalEndpoint());
        if (deployment.getLbEndpoint() == null) {
            m.put("loadBalancer", null);
        } else {
            Map<String, Object> lb = new LinkedHashMap<>();
            lb.put("containerName", deployment.getLbContainerName());
            lb.put("hostPort", deployment.getLbHostPort());
            lb.put("status", deployment.getLbStatus());
            lb.put("endpoint", deployment.getLbEndpoint());
            lb.put("localEndpoint", deployment.getLbLocalEndpoint());
            m.put("loadBalancer", lb);
        }
        m.put("status", deployment.getStatus());
        m.put("hasToken", deployment.getRuntimeToken() != null && !deployment.getRuntimeToken().isBlank());
        m.put("lastError", deployment.getLastError());
        m.put("updatedAt", deployment.getUpdatedAt());
        m.put("apiDocs", deployment.getEndpoint() == null ? null
                : deployment.getEndpoint().replaceAll("/v1$", "") + "/v1/chat/completions");
        return m;
    }

    private String message(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
