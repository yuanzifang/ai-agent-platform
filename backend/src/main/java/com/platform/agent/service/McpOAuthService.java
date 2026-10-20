package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.McpServer;
import com.platform.agent.mapper.McpServerMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP OAuth 2.1（PKCE / 动态客户端注册 / 授权服务器发现 / token 刷新）。
 *
 * - 发现：RFC 9728 protected-resource metadata → RFC 8414 authorization-server metadata
 * - 授权：authorization code + S256 PKCE，state 存 Redis（10 分钟，一次性）
 * - 存储：token 以 AES-GCM 密文保存在 mcp_server.encrypted_auth；刷新时静默更新
 * - 兼容：历史 OAUTH 配置里的 token 字段仍按静态 Bearer 使用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpOAuthService {

    private static final String STATE_PREFIX = "mcp:oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final long EXPIRY_SKEW_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final McpServerMapper serverMapper;
    private final CryptoService cryptoService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Value("${app.public-base:http://localhost:8080}")
    private String publicBase;

    @Value("${app.frontend-base:http://localhost:5173}")
    private String frontendBase;

    private final Map<String, CachedMeta> metaCache = new ConcurrentHashMap<>();
    private final Map<Long, Object> refreshLocks = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    public record OAuthMeta(String issuer, String authorizationEndpoint, String tokenEndpoint,
                            String registrationEndpoint, List<String> scopesSupported,
                            List<String> tokenAuthMethods, String resource) {
    }

    private record CachedMeta(OAuthMeta meta, long expiresAt) {
    }

    private record OAuthState(Long serverId, Long tenantId, Long userId, String verifier,
                              String redirectUri, String resource, String tokenEndpoint,
                              String clientId, String clientSecret, String authMethod) {
    }

    // ---------- 授权入口 ----------

    public Map<String, Object> authorizeUrl(McpServer server, JwtPrincipal principal) {
        if (!"OAUTH".equalsIgnoreCase(server.getAuthType())) {
            throw new BusinessException(400, "该 MCP Server 的鉴权方式不是 OAuth");
        }
        OAuthMeta meta;
        try {
            meta = discover(server);
        } catch (Exception e) {
            throw new BusinessException(400, "OAuth 发现失败：" + msg(e));
        }
        Map<String, Object> auth = authOf(server);
        String callback = publicBase + "/api/v1/mcp-servers/oauth/callback";
        String clientId = str(auth.get("client_id"));
        String clientSecret = str(auth.get("client_secret"));
        String authMethod = str(auth.get("token_endpoint_auth_method"));
        if (clientId.isBlank()) {
            if (meta.registrationEndpoint() == null || meta.registrationEndpoint().isBlank()) {
                throw new BusinessException(400,
                        "OAuth 服务器未提供动态客户端注册端点，请先在鉴权配置中填写 client_id/client_secret");
            }
            try {
                Map<String, Object> reg = registerClient(meta, callback);
                clientId = str(reg.get("client_id"));
                clientSecret = str(reg.get("client_secret"));
                authMethod = str(reg.getOrDefault("token_endpoint_auth_method", "none"));
                auth.put("client_id", clientId);
                auth.put("client_secret", clientSecret);
                auth.put("token_endpoint_auth_method", authMethod);
            } catch (Exception e) {
                throw new BusinessException(400, "OAuth 动态客户端注册失败：" + msg(e));
            }
        }
        auth.put("token_endpoint", meta.tokenEndpoint());
        auth.put("resource", meta.resource());
        persist(server, auth);

        String verifier = random(48);
        String state = random(36);
        OAuthState payload = new OAuthState(server.getId(), TenantContext.getTenantId(),
                principal == null ? null : principal.userId(), verifier, callback, meta.resource(),
                meta.tokenEndpoint(), clientId, clientSecret, authMethod);
        try {
            redis.opsForValue().set(STATE_PREFIX + state,
                    objectMapper.writeValueAsString(payload), STATE_TTL);
        } catch (Exception e) {
            throw new BusinessException(500, "OAuth state 保存失败：" + msg(e));
        }
        StringBuilder url = new StringBuilder(meta.authorizationEndpoint());
        url.append(meta.authorizationEndpoint().contains("?") ? "&" : "?")
                .append("response_type=code")
                .append("&client_id=").append(enc(clientId))
                .append("&redirect_uri=").append(enc(callback))
                .append("&state=").append(enc(state))
                .append("&code_challenge=").append(enc(challenge(verifier)))
                .append("&code_challenge_method=S256");
        if (!meta.resource().isBlank()) url.append("&resource=").append(enc(meta.resource()));
        String scope = str(auth.get("scope"));
        if (!scope.isBlank()) url.append("&scope=").append(enc(scope));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorizeUrl", url.toString());
        result.put("state", state);
        result.put("redirectUri", callback);
        result.put("clientId", clientId);
        return result;
    }

    /** 浏览器回调：校验 state → 换取 token → 加密落库 → 302 回前端。 */
    public String callback(String code, String state, String error, String errorDescription) {
        if (error != null && !error.isBlank()) {
            return frontend("error", null, errorDescription == null || errorDescription.isBlank() ? error : errorDescription);
        }
        if (state == null || state.isBlank()) return frontend("error", null, "缺少 state 参数");
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(STATE_PREFIX + state);
        } catch (Exception e) {
            return frontend("error", null, "state 校验失败");
        }
        if (raw == null) return frontend("error", null, "授权状态已失效，请重新发起授权");
        OAuthState st;
        try {
            st = objectMapper.readValue(raw, OAuthState.class);
        } catch (Exception e) {
            return frontend("error", null, "授权状态解析失败");
        }
        try {
            return TenantContext.runWith(st.tenantId(), () -> {
                if (code == null || code.isBlank()) return frontend("error", st.serverId(), "缺少授权码");
                JsonNode token = exchangeCode(st, code);
                McpServer server = serverMapper.selectById(st.serverId());
                if (server == null) return frontend("error", st.serverId(), "MCP Server 不存在");
                Map<String, Object> auth = authOf(server);
                mergeToken(auth, token, st.tokenEndpoint(), st.clientId(), st.clientSecret(), st.authMethod());
                persist(server, auth);
                auditService.record(st.userId(), "mcp_server.oauth", "mcp_server",
                        String.valueOf(st.serverId()), "SUCCESS",
                        Map.of("connected", true, "expiresIn", token.path("expires_in").asLong(0)));
                log.info("MCP OAuth 授权成功 server={}", st.serverId());
                return frontend("success", st.serverId(), null);
            });
        } catch (Exception e) {
            log.warn("MCP OAuth 授权失败 server={}：{}", st.serverId(), msg(e));
            try {
                TenantContext.runWith(st.tenantId(), () -> {
                    auditService.record(st.userId(), "mcp_server.oauth", "mcp_server",
                            String.valueOf(st.serverId()), "FAILED", Map.of("error", truncate(msg(e), 200)));
                    return null;
                });
            } catch (Exception auditError) {
                log.warn("OAuth 审计写入失败：{}", auditError.getMessage());
            }
            return frontend("error", st.serverId(), msg(e));
        }
    }

    public Map<String, Object> status(McpServer server) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oauth", "OAUTH".equalsIgnoreCase(server.getAuthType()));
        Map<String, Object> auth = authOf(server);
        boolean connected = !str(auth.get("access_token")).isBlank() || !str(auth.get("token")).isBlank();
        long expiresAt = longVal(auth.get("expires_at"));
        result.put("connected", connected);
        result.put("expiresAt", expiresAt == 0 ? null : expiresAt);
        result.put("expired", expiresAt > 0 && expiresAt <= System.currentTimeMillis());
        result.put("refreshable", !str(auth.get("refresh_token")).isBlank());
        result.put("scope", str(auth.get("scope")));
        result.put("clientId", str(auth.get("client_id")));
        result.put("legacy", connected && str(auth.get("access_token")).isBlank());
        return result;
    }

    /** 取出可用 access token；过期前 60 秒内自动刷新。 */
    public String accessToken(McpServer server) {
        Map<String, Object> auth = authOf(server);
        String legacy = str(auth.get("token"));
        if (!legacy.isBlank() && str(auth.get("access_token")).isBlank()) {
            return legacy; // 历史静态 token，保持兼容
        }
        String access = str(auth.get("access_token"));
        long expiresAt = longVal(auth.get("expires_at"));
        if (!access.isBlank() && (expiresAt == 0 || expiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS)) {
            return access;
        }
        synchronized (refreshLocks.computeIfAbsent(server.getId(), k -> new Object())) {
            Map<String, Object> latest = authOf(server);
            String token = str(latest.get("access_token"));
            long exp = longVal(latest.get("expires_at"));
            if (!token.isBlank() && (exp == 0 || exp > System.currentTimeMillis() + EXPIRY_SKEW_MS)) {
                return token;
            }
            return refresh(server, latest);
        }
    }

    /** 收到 401 后强制刷新一次（忽略本地过期时间）。 */
    public String forceRefresh(McpServer server) {
        synchronized (refreshLocks.computeIfAbsent(server.getId(), k -> new Object())) {
            return refresh(server, authOf(server));
        }
    }

    /** 断开授权：保留动态客户端注册信息，清除 token。 */
    public void disconnect(McpServer server) {
        Map<String, Object> auth = authOf(server);
        auth.remove("access_token");
        auth.remove("refresh_token");
        auth.remove("expires_at");
        auth.remove("token");
        auth.remove("scope");
        persist(server, auth);
    }

    // ---------- token ----------

    private String refresh(McpServer server, Map<String, Object> auth) {
        String refreshToken = str(auth.get("refresh_token"));
        if (refreshToken.isBlank()) {
            throw new IllegalStateException("MCP OAuth 未授权或授权已过期，请点击「OAuth 授权」重新连接");
        }
        String tokenEndpoint = str(auth.get("token_endpoint"));
        if (tokenEndpoint.isBlank()) {
            throw new IllegalStateException("缺少 OAuth token_endpoint，请重新授权");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", str(auth.get("client_id")));
        try {
            HttpResponse<String> resp = postForm(tokenEndpoint, form,
                    str(auth.get("client_secret")), str(auth.get("token_endpoint_auth_method")));
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("OAuth token 刷新失败：HTTP " + resp.statusCode()
                        + " " + truncate(resp.body(), 200) + "（请重新授权）");
            }
            JsonNode json = objectMapper.readTree(resp.body());
            mergeToken(auth, json, tokenEndpoint, str(auth.get("client_id")),
                    str(auth.get("client_secret")), str(auth.get("token_endpoint_auth_method")));
            persist(server, auth);
            log.info("MCP OAuth token 已刷新 server={} expiresIn={}", server.getId(),
                    json.path("expires_in").asLong(0));
            return str(auth.get("access_token"));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OAuth token 刷新失败：" + msg(e));
        }
    }

    private JsonNode exchangeCode(OAuthState st, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", st.redirectUri());
        form.put("client_id", st.clientId());
        form.put("code_verifier", st.verifier());
        if (st.resource() != null && !st.resource().isBlank()) form.put("resource", st.resource());
        try {
            HttpResponse<String> resp = postForm(st.tokenEndpoint(), form, st.clientSecret(), st.authMethod());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("OAuth 换取 token 失败：HTTP " + resp.statusCode()
                        + " " + truncate(resp.body(), 200));
            }
            JsonNode json = objectMapper.readTree(resp.body());
            if (!json.hasNonNull("access_token")) {
                throw new IllegalStateException("OAuth token 响应缺少 access_token");
            }
            return json;
        } catch (Exception e) {
            throw new IllegalStateException("OAuth 换取 token 失败：" + msg(e));
        }
    }

    private void mergeToken(Map<String, Object> auth, JsonNode token, String tokenEndpoint,
                            String clientId, String clientSecret, String authMethod) {
        auth.put("access_token", token.path("access_token").asText(""));
        if (token.hasNonNull("refresh_token")) {
            auth.put("refresh_token", token.path("refresh_token").asText());
        }
        long expiresIn = token.path("expires_in").asLong(3600);
        auth.put("expires_at", System.currentTimeMillis() + Math.max(expiresIn, 1) * 1000L);
        auth.put("token_type", token.path("token_type").asText("Bearer"));
        if (token.hasNonNull("scope")) auth.put("scope", token.path("scope").asText());
        if (tokenEndpoint != null && !tokenEndpoint.isBlank()) auth.put("token_endpoint", tokenEndpoint);
        if (clientId != null && !clientId.isBlank()) auth.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) auth.put("client_secret", clientSecret);
        if (authMethod != null && !authMethod.isBlank()) auth.put("token_endpoint_auth_method", authMethod);
        String access = str(auth.get("access_token"));
        if (access.isBlank()) throw new IllegalStateException("OAuth token 为空");
    }

    private HttpResponse<String> postForm(String url, Map<String, String> form,
                                          String clientSecret, String authMethod) throws Exception {
        boolean basic = "client_secret_basic".equalsIgnoreCase(authMethod)
                && clientSecret != null && !clientSecret.isBlank();
        if (!basic && clientSecret != null && !clientSecret.isBlank()) {
            form.put("client_secret", clientSecret);
        }
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (basic) {
            String clientId = form.getOrDefault("client_id", "");
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------- 发现与注册 ----------

    private OAuthMeta discover(McpServer server) throws Exception {
        String cacheKey = server.getEndpoint();
        CachedMeta cached = metaCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.meta();

        URI endpoint = URI.create(server.getEndpoint());
        String origin = endpoint.getScheme() + "://" + endpoint.getAuthority();
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        List<String> prmUrls = new ArrayList<>();
        if (!path.isBlank() && !"/".equals(path)) {
            prmUrls.add(origin + "/.well-known/oauth-protected-resource" + path);
        }
        prmUrls.add(origin + "/.well-known/oauth-protected-resource");
        JsonNode prm = getJsonFirst(prmUrls);
        String issuer = origin;
        String resource = server.getEndpoint();
        if (prm != null) {
            if (!prm.path("resource").asText("").isBlank()) resource = prm.path("resource").asText();
            JsonNode servers = prm.path("authorization_servers");
            if (servers.isArray() && !servers.isEmpty()) issuer = servers.get(0).asText();
        }
        List<String> metaUrls = new ArrayList<>();
        metaUrls.add(wellKnown(issuer, "oauth-authorization-server"));
        metaUrls.add(issuer + "/.well-known/oauth-authorization-server");
        metaUrls.add(wellKnown(issuer, "openid-configuration"));
        metaUrls.add(issuer + "/.well-known/openid-configuration");
        JsonNode meta = getJsonFirst(metaUrls);
        if (meta == null) {
            throw new IllegalStateException("未找到 OAuth 授权服务器元数据（" + issuer + "）");
        }
        String authEndpoint = meta.path("authorization_endpoint").asText("");
        String tokenEndpoint = meta.path("token_endpoint").asText("");
        if (authEndpoint.isBlank() || tokenEndpoint.isBlank()) {
            throw new IllegalStateException("OAuth 元数据缺少 authorization_endpoint/token_endpoint");
        }
        List<String> scopes = new ArrayList<>();
        meta.path("scopes_supported").forEach(n -> scopes.add(n.asText()));
        List<String> authMethods = new ArrayList<>();
        meta.path("token_endpoint_auth_methods_supported").forEach(n -> authMethods.add(n.asText()));
        OAuthMeta result = new OAuthMeta(issuer, authEndpoint, tokenEndpoint,
                meta.path("registration_endpoint").asText(null), scopes, authMethods, resource);
        metaCache.put(cacheKey, new CachedMeta(result, System.currentTimeMillis() + 10 * 60_000L));
        return result;
    }

    private Map<String, Object> registerClient(OAuthMeta meta, String callback) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", "AI Agent Platform");
        body.put("redirect_uris", List.of(callback));
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        String method = "none";
        if (!meta.tokenAuthMethods().isEmpty() && !meta.tokenAuthMethods().contains("none")) {
            method = meta.tokenAuthMethods().contains("client_secret_post")
                    ? "client_secret_post" : meta.tokenAuthMethods().get(0);
        }
        body.put("token_endpoint_auth_method", method);
        HttpRequest request = HttpRequest.newBuilder(URI.create(meta.registrationEndpoint()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + truncate(resp.body(), 200));
        }
        JsonNode json = objectMapper.readTree(resp.body());
        String clientId = json.path("client_id").asText("");
        if (clientId.isBlank()) throw new IllegalStateException("注册响应缺少 client_id");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client_id", clientId);
        if (json.hasNonNull("client_secret")) result.put("client_secret", json.path("client_secret").asText());
        result.put("token_endpoint_auth_method",
                json.path("token_endpoint_auth_method").asText(method));
        return result;
    }

    private JsonNode getJsonFirst(List<String> urls) {
        for (String url : urls) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(HTTP_TIMEOUT)
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    return objectMapper.readTree(resp.body());
                }
            } catch (Exception e) {
                log.debug("OAuth 元数据探测失败 {}：{}", url, e.getMessage());
            }
        }
        return null;
    }

    private String wellKnown(String issuer, String suffix) {
        URI u = URI.create(issuer);
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return u.getScheme() + "://" + u.getAuthority() + "/.well-known/" + suffix + path;
    }

    // ---------- 存储 ----------

    private Map<String, Object> authOf(McpServer server) {
        if (server.getEncryptedAuth() == null || server.getEncryptedAuth().isBlank()) return new LinkedHashMap<>();
        try {
            String plain = cryptoService.decrypt(server.getEncryptedAuth());
            if (plain == null || plain.isBlank()) return new LinkedHashMap<>();
            return new LinkedHashMap<>(objectMapper.readValue(plain, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException("MCP 鉴权信息解密失败：AES_KEY 可能已变更");
        }
    }

    private void persist(McpServer server, Map<String, Object> auth) {
        try {
            String encrypted = cryptoService.encrypt(objectMapper.writeValueAsString(auth));
            server.setEncryptedAuth(encrypted);
            McpServer patch = new McpServer();
            patch.setId(server.getId());
            patch.setEncryptedAuth(encrypted);
            patch.setUpdatedAt(LocalDateTime.now());
            serverMapper.updateById(patch);
        } catch (Exception e) {
            throw new IllegalStateException("MCP OAuth 凭证保存失败：" + msg(e));
        }
    }

    // ---------- 工具 ----------

    private String frontend(String status, Long serverId, String message) {
        StringBuilder url = new StringBuilder(frontendBase).append("/mcp-servers?oauth=").append(status);
        if (serverId != null) url.append("&serverId=").append(serverId);
        if (message != null && !message.isBlank()) url.append("&message=").append(enc(truncate(message, 300)));
        return url.toString();
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return B64URL.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("PKCE 计算失败", e);
        }
    }

    private static String random(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return B64URL.encodeToString(data);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longVal(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String msg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
