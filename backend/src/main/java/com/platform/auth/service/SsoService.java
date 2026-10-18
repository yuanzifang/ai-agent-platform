package com.platform.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysTenant;
import com.platform.system.entity.SysTenantConfig;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysTenantConfigMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SSO（OAuth2/OIDC 授权码，需求 3.25(5) / P1）：租户级配置、发现文档、授权跳转、回调换取 Token、
 * 自动开通用户（可配置默认角色）。配置存 sys_tenant_config，client_secret AES-GCM 加密。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoService {

    private static final String K_ENABLED = "sso_enabled";
    private static final String K_ISSUER = "sso_issuer";
    private static final String K_CLIENT_ID = "sso_client_id";
    private static final String K_CLIENT_SECRET = "sso_client_secret";
    private static final String K_SCOPES = "sso_scopes";
    private static final String K_AUTO_PROVISION = "sso_auto_provision";
    private static final String K_DEFAULT_ROLE = "sso_default_role";
    private static final String K_BUTTON_NAME = "sso_button_name";
    private static final String K_REDIRECT_URI = "sso_redirect_uri";

    private final SysTenantMapper tenantMapper;
    private final SysTenantConfigMapper configMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${app.frontend-base:http://localhost:5173}")
    private String frontendBase;

    private final Map<String, CachedDiscovery> discoveryCache = new ConcurrentHashMap<>();

    private record CachedDiscovery(String authorizationEndpoint, String tokenEndpoint,
                                   String userinfoEndpoint, long cachedAt) {
    }

    // ---------- 设置 ----------

    public Map<String, Object> settings() {
        Long tenantId = TenantContext.getTenantId();
        Map<String, String> cfg = configs(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", "true".equalsIgnoreCase(cfg.get(K_ENABLED)));
        m.put("issuer", cfg.getOrDefault(K_ISSUER, ""));
        m.put("clientId", cfg.getOrDefault(K_CLIENT_ID, ""));
        m.put("clientSecretSet", cfg.get(K_CLIENT_SECRET) != null && !cfg.get(K_CLIENT_SECRET).isBlank());
        m.put("scopes", cfg.getOrDefault(K_SCOPES, "openid profile email"));
        m.put("autoProvision", "true".equalsIgnoreCase(cfg.get(K_AUTO_PROVISION)));
        m.put("defaultRole", cfg.getOrDefault(K_DEFAULT_ROLE, "MEMBER"));
        m.put("buttonName", cfg.getOrDefault(K_BUTTON_NAME, "企业 SSO 登录"));
        m.put("redirectUri", cfg.getOrDefault(K_REDIRECT_URI, defaultRedirect()));
        return m;
    }

    @Transactional
    public void update(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = principal.tenantId();
        if (req.containsKey("enabled")) set(tenantId, K_ENABLED, String.valueOf(Boolean.TRUE.equals(req.get("enabled"))));
        if (req.containsKey("issuer")) {
            String issuer = str(req.get("issuer"));
            if (issuer != null && !issuer.isBlank()
                    && !issuer.startsWith("http://") && !issuer.startsWith("https://")) {
                throw new BusinessException(400, "Issuer 仅支持 http/https");
            }
            set(tenantId, K_ISSUER, issuer == null ? "" : issuer.trim().replaceAll("/+$", ""));
        }
        if (req.containsKey("clientId")) set(tenantId, K_CLIENT_ID, str(req.get("clientId")));
        if (req.containsKey("clientSecret")) {
            String secret = str(req.get("clientSecret"));
            if (secret != null && !secret.isBlank() && !"******".equals(secret)) {
                set(tenantId, K_CLIENT_SECRET, cryptoService.encrypt(secret));
            }
        }
        if (req.containsKey("scopes")) set(tenantId, K_SCOPES, str(req.get("scopes")));
        if (req.containsKey("autoProvision")) set(tenantId, K_AUTO_PROVISION, String.valueOf(Boolean.TRUE.equals(req.get("autoProvision"))));
        if (req.containsKey("defaultRole")) {
            String role = req.get("defaultRole") == null ? "MEMBER" : String.valueOf(req.get("defaultRole")).toUpperCase();
            if (!List.of("MEMBER", "EDITOR").contains(role)) throw new BusinessException(400, "默认角色仅支持 MEMBER/EDITOR");
            set(tenantId, K_DEFAULT_ROLE, role);
        }
        if (req.containsKey("buttonName")) set(tenantId, K_BUTTON_NAME, str(req.get("buttonName")));
        if (req.containsKey("redirectUri")) {
            String uri = str(req.get("redirectUri"));
            if (uri != null && !uri.isBlank()
                    && !uri.startsWith("http://") && !uri.startsWith("https://")) {
                throw new BusinessException(400, "回调地址仅支持 http/https");
            }
            set(tenantId, K_REDIRECT_URI, uri == null ? "" : uri.trim());
        }
        auditService.record(principal.userId(), "sso.settings.update", "tenant",
                String.valueOf(tenantId), "SUCCESS", Map.of());
    }

    // ---------- 登录流程 ----------

    public Map<String, Object> available(String tenantKey) {
        SysTenant tenant = tenant(tenantKey);
        return TenantContext.runWith(tenant.getId(), () -> {
            Map<String, String> cfg = configs(tenant.getId());
            boolean enabled = "true".equalsIgnoreCase(cfg.get(K_ENABLED));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled && notBlank(cfg.get(K_ISSUER)) && notBlank(cfg.get(K_CLIENT_ID)));
            m.put("buttonName", cfg.getOrDefault(K_BUTTON_NAME, "企业 SSO 登录"));
            m.put("tenantKey", tenant.getTenantKey());
            return m;
        });
    }

    public Map<String, Object> authorize(String tenantKey) {
        SysTenant tenant = tenant(tenantKey);
        return TenantContext.runWith(tenant.getId(), () -> authorizeInternal(tenant));
    }

    private Map<String, Object> authorizeInternal(SysTenant tenant) {
        Map<String, String> cfg = configs(tenant.getId());
        if (!"true".equalsIgnoreCase(cfg.get(K_ENABLED))) throw new BusinessException(400, "该租户未启用 SSO");
        Discovery d = discovery(required(cfg, K_ISSUER));
        String state = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("sso:state:" + state, String.valueOf(tenant.getId()), 10, TimeUnit.MINUTES);
        String redirectUri = notBlank(cfg.get(K_REDIRECT_URI)) ? cfg.get(K_REDIRECT_URI) : defaultRedirect();
        String url = d.authorizationEndpoint()
                + (d.authorizationEndpoint().contains("?") ? "&" : "?")
                + "response_type=code&client_id=" + enc(required(cfg, K_CLIENT_ID))
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(cfg.getOrDefault(K_SCOPES, "openid profile email"))
                + "&state=" + enc(state);
        return Map.of("authorizeUrl", url, "state", state);
    }

    @Transactional
    public AuthService.LoginResult exchange(String code, String state,
                                            jakarta.servlet.http.HttpServletRequest request) {
        if (code == null || code.isBlank()) throw new BusinessException(400, "缺少 code");
        if (state == null || state.isBlank()) throw new BusinessException(400, "缺少 state");
        String key = "sso:state:" + state;
        String tenantIdStr = redis.opsForValue().get(key);
        if (tenantIdStr == null) throw new BusinessException(400, "SSO 状态无效或已过期，请重新登录");
        redis.delete(key);
        Long tenantId = Long.parseLong(tenantIdStr);
        return TenantContext.runWith(tenantId, () -> doExchange(tenantId, code, request));
    }

    private AuthService.LoginResult doExchange(Long tenantId, String code,
                                                jakarta.servlet.http.HttpServletRequest request) {
        Map<String, String> cfg = configs(tenantId);
        if (!"true".equalsIgnoreCase(cfg.get(K_ENABLED))) throw new BusinessException(400, "该租户未启用 SSO");

        Discovery d = discovery(required(cfg, K_ISSUER));
        String redirectUri = notBlank(cfg.get(K_REDIRECT_URI)) ? cfg.get(K_REDIRECT_URI) : defaultRedirect();
        String clientSecret = cfg.get(K_CLIENT_SECRET) == null ? "" : cryptoService.decrypt(cfg.get(K_CLIENT_SECRET));
        String form = "grant_type=authorization_code&code=" + enc(code)
                + "&client_id=" + enc(required(cfg, K_CLIENT_ID))
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(redirectUri);
        JsonNode token = postForm(d.tokenEndpoint(), form);
        String accessToken = token.path("access_token").asText(null);
        if (accessToken == null) throw new BusinessException(502, "SSO Token 交换失败");
        JsonNode info = getJson(d.userinfoEndpoint(), accessToken);
        String rawUsername = firstNotBlank(info.path("preferred_username").asText(null),
                info.path("email").asText(null), info.path("sub").asText(null));
        if (rawUsername == null) throw new BusinessException(502, "SSO 用户信息缺少标识");
        String username = sanitizeUsername(rawUsername);
        String displayName = firstNotBlank(info.path("name").asText(null), username);

        SysUser user = userMapper.findByUsername(username);
        if (user == null) {
            if (!"true".equalsIgnoreCase(cfg.get(K_AUTO_PROVISION))) {
                throw new BusinessException(403, "账号未开通，请联系管理员");
            }
            user = new SysUser();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setDisplayName(displayName);
            user.setIsPlatformAdmin(0);
            user.setIsServiceAccount(0);
            user.setTenantId(tenantId);
            user.setStatus("ENABLED");
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
            SysRole role = roleMapper.findBuiltinByKey(cfg.getOrDefault(K_DEFAULT_ROLE, "MEMBER"));
            if (role != null) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(user.getId());
                ur.setRoleId(role.getId());
                userRoleMapper.insert(ur);
            }
            auditService.record(user.getId(), "sso.user.provision", "user",
                    String.valueOf(user.getId()), "SUCCESS",
                    Map.of("username", username, "tenantId", tenantId));
        } else if (!tenantId.equals(user.getTenantId())) {
            throw new BusinessException(409, "账号已存在但归属其他租户，请联系管理员");
        }
        return authService.loginAs(user.getId(), request);
    }

    // ---------- OIDC 辅助 ----------

    private record Discovery(String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint) {
    }

    private Discovery discovery(String issuer) {
        String base = issuer.replaceAll("/+$", "");
        CachedDiscovery cached = discoveryCache.get(base);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt() < 10 * 60_000L) {
            return new Discovery(cached.authorizationEndpoint(), cached.tokenEndpoint(), cached.userinfoEndpoint());
        }
        try {
            JsonNode node = getJson(base + "/.well-known/openid-configuration", null);
            String auth = node.path("authorization_endpoint").asText(null);
            String token = node.path("token_endpoint").asText(null);
            String userinfo = node.path("userinfo_endpoint").asText(null);
            if (auth == null || token == null || userinfo == null) {
                throw new BusinessException(400, "OIDC 发现文档缺少必要端点");
            }
            discoveryCache.put(base, new CachedDiscovery(auth, token, userinfo, System.currentTimeMillis()));
            return new Discovery(auth, token, userinfo);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "OIDC 发现失败：" + rootMessage(e));
        }
    }

    private JsonNode postForm(String url, String form) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new BusinessException(502, "SSO Token 交换失败：HTTP " + resp.statusCode());
            }
            return objectMapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "SSO Token 交换失败：" + rootMessage(e));
        }
    }

    private JsonNode getJson(String url, String bearer) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET();
            if (bearer != null) b.header("Authorization", "Bearer " + bearer);
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) throw new BusinessException(502, "请求失败：HTTP " + resp.statusCode());
            return objectMapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "请求失败：" + rootMessage(e));
        }
    }

    // ---------- 配置辅助 ----------

    public Map<String, Object> publicConfig(String tenantKey) {
        return available(tenantKey);
    }

    private SysTenant tenant(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) throw new BusinessException(400, "缺少租户标识");
        SysTenant tenant = tenantMapper.findByKey(tenantKey.trim());
        if (tenant == null) throw new BusinessException(404, "租户不存在");
        return tenant;
    }

    private Map<String, String> configs(Long tenantId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SysTenantConfig c : configMapper.selectList(new LambdaQueryWrapper<SysTenantConfig>()
                .eq(SysTenantConfig::getTenantId, tenantId))) {
            map.put(c.getConfigKey(), fromJsonValue(c.getConfigValue()));
        }
        return map;
    }

    private void set(Long tenantId, String key, String value) {
        SysTenantConfig existing = configMapper.selectOne(new LambdaQueryWrapper<SysTenantConfig>()
                .eq(SysTenantConfig::getTenantId, tenantId)
                .eq(SysTenantConfig::getConfigKey, key)
                .last("LIMIT 1"));
        if (existing == null) {
            SysTenantConfig c = new SysTenantConfig();
            c.setTenantId(tenantId);
            c.setConfigKey(key);
            c.setConfigValue(toJsonValue(value));
            c.setUpdatedAt(LocalDateTime.now());
            configMapper.insert(c);
        } else {
            existing.setConfigValue(toJsonValue(value));
            existing.setUpdatedAt(LocalDateTime.now());
            configMapper.updateById(existing);
        }
    }

    /** JSON 列：合法 JSON 原样写入，普通字符串 JSON 转义。 */
    private String toJsonValue(String value) {
        if (value == null) return "null";
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                throw new BusinessException(400, "配置值序列化失败");
            }
        }
    }

    private String fromJsonValue(String raw) {
        if (raw == null) return null;
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isTextual() ? node.asText() : node.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private String defaultRedirect() {
        return frontendBase.replaceAll("/+$", "") + "/sso/callback";
    }

    private String required(Map<String, String> cfg, String key) {
        String v = cfg.get(key);
        if (v == null || v.isBlank()) throw new BusinessException(400, "SSO 配置不完整（缺少 " + key + "）");
        return v;
    }

    private String sanitizeUsername(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        if (name.length() > 64) name = name.substring(0, 64);
        return name.isBlank() ? "sso_" + UUID.randomUUID().toString().substring(0, 8) : name;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String firstNotBlank(String... values) {
        for (String v : values) if (notBlank(v)) return v;
        return null;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
