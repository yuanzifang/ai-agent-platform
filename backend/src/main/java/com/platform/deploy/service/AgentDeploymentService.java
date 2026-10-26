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
        } else {
            removeContainerQuietly(deployment.getContainerName());
        }
        // 旧 Key 先清理，避免每 Agent 10 个 Key 的上限被重复部署占满
        deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);

        int port = allocatePort(deployment);
        String containerName = deployment.getContainerName();
        String slug = "agent-" + agentId;
        String apiKey;
        try {
            Map<String, Object> key = apiKeyService.create(agentId, Map.of(
                    "name", "container:" + containerName,
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
        deployment.setHostPort(port);
        deployment.setStatus("PENDING");
        deployment.setLastError(null);
        deployment.setDeployedBy(principal.userId());
        deployment.setVersion(latestVersion(agentId));
        deployment.setEndpoint(publicEndpoint(port));
        deployment.setLocalEndpoint("http://localhost:" + port + "/v1");
        save(deployment, existed);

        try {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PLATFORM_BASE", platformBase.replaceAll("/+$", ""));
            env.put("API_KEY", apiKey);
            env.put("AGENT_SLUG", slug);
            env.put("RUNTIME_TOKEN", ensureRuntimeToken(deployment));
            env.put("RATE_BURST", String.valueOf(Math.max(0, rateLimitBurst)));
            env.put("CORS_ORIGIN", corsOrigins == null || corsOrigins.isBlank() ? "*" : corsOrigins.trim());
            List<String> mounts;
            if (gateway) {
                // 自研运行时：镜像内置网关逻辑，不再挂 nginx 模板
                env.put("PORT", "8080");
                env.put("RATE_LIMIT_PER_MIN", String.valueOf(Math.max(0, rateLimitPerMin)));
                env.put("CALLER_QUOTA_DAILY", String.valueOf(Math.max(0, callerQuotaDaily)));
                List<Map<String, Object>> callerKeyPayload = new ArrayList<>();
                for (AgentRuntimeKey key : runtimeKeyService.enabledKeys(agentId)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("hash", key.getKeyHash());
                    row.put("quota", key.getDailyQuota() == null ? 0 : key.getDailyQuota());
                    row.put("name", key.getName());
                    callerKeyPayload.add(row);
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

            ContainerRuntime.CreateRequest request = new ContainerRuntime.CreateRequest(
                    containerName,
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
            // 启动后做一次真实健康检查：nginx 配置写错时会 crash-loop，不能只看 docker 状态
            waitForHealthy(port);

            deployment.setContainerId(containerId);
            deployment.setStatus("RUNNING");
            save(deployment, true);
            auditService.record(principal.userId(), "agent.deploy", "agent", String.valueOf(agentId),
                    "SUCCESS", Map.of("container", containerName, "port", port));
            recordEvent(deployment, "DEPLOY", "SUCCESS", null, principal);
            return view(deployment);
        } catch (Exception e) {
            removeContainerQuietly(containerName);
            deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);
            deployment.setStatus("FAILED");
            deployment.setLastError(message(e));
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
        runtime.start(deployment.getContainerName());
        deployment.setStatus("RUNNING");
        deployment.setLastError(null);
        save(deployment, true);
        recordEvent(deployment, "START", "SUCCESS", null, principal);
        return view(deployment);
    }

    public Map<String, Object> stop(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = requireDeployment(agentId);
        runtime.stop(deployment.getContainerName());
        deployment.setStatus("STOPPED");
        save(deployment, true);
        recordEvent(deployment, "STOP", "SUCCESS", null, principal);
        return view(deployment);
    }

    @Transactional
    public void remove(Long agentId, JwtPrincipal principal) {
        AgentDeployment deployment = requireDeployment(agentId);
        removeContainerQuietly(deployment.getContainerName());
        deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);
        recordEvent(deployment, "REMOVE", "SUCCESS", null, principal);
        deploymentMapper.deleteById(deployment.getId());
        auditService.record(principal.userId(), "agent.undeploy", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("container", deployment.getContainerName()));
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
        java.net.http.HttpRequest request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://127.0.0.1:" + deployment.getHostPort() + "/stats"))
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
        removeContainerQuietly(deployment.getContainerName());
        deleteKeyQuietly(agentId, deployment.getApiKeyId(), principal);
        recordEvent(deployment, "REMOVE", "SUCCESS", "agent deleted", principal);
        deploymentMapper.deleteById(deployment.getId());
    }

    public String logs(Long agentId, int tail) {
        AgentDeployment deployment = requireDeployment(agentId);
        return runtime.logs(deployment.getContainerName(), Math.min(Math.max(tail, 1), 1000));
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
        if (deployment.getContainerId() == null || deployment.getContainerId().isBlank()) return;
        try {
            ContainerRuntime.Info info = runtime.inspect(deployment.getContainerName());
            String next = info.running() ? "RUNNING" : "STOPPED";
            if (!next.equals(deployment.getStatus())) {
                deployment.setStatus(next);
                save(deployment, true);
            }
        } catch (BusinessException e) {
            deployment.setStatus("FAILED");
            deployment.setLastError(e.getMessage());
            save(deployment, true);
        }
    }

    private int allocatePort(AgentDeployment deployment) {
        Set<Integer> used = new HashSet<>();
        for (AgentDeployment d : deploymentMapper.selectList(null)) {
            if (d.getHostPort() != null && !d.getId().equals(deployment.getId())) used.add(d.getHostPort());
        }
        // 优先复用原端口：客户端拿到的地址尽量不因重新部署而变化
        if (deployment.getHostPort() != null && !used.contains(deployment.getHostPort())) {
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
            if (!used.contains(port) && portFree(port)) return port;
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
            // 默认放用户目录下：macOS 上 Docker/colima 默认只共享 $HOME，
            // 系统临时目录（/var/folders/...）容器里挂载不到。
            Path base = (workdir == null || workdir.isBlank())
                    ? Path.of(System.getProperty("user.home"), ".aap-agent-runtime")
                    : Path.of(workdir);
            Path dir = base.resolve("templates");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("default.conf.template"), NGINX_TEMPLATE, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new BusinessException(500, "写入容器配置模板失败：" + message(e));
        }
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
