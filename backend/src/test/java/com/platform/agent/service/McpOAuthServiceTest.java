package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.McpServer;
import com.platform.agent.mapper.McpServerMapper;
import com.platform.common.security.CryptoService;
import com.platform.system.service.AuditService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MCP OAuth 2.1：发现、PKCE、动态客户端注册、token 刷新。 */
@SuppressWarnings("unchecked")
class McpOAuthServiceTest {

    private HttpServer http;
    private String base;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CryptoService cryptoService =
            new CryptoService("test-aes-key-0123456789abcdef0123456789", "test-jwt-secret");
    private final AtomicInteger refreshCount = new AtomicInteger();
    private String lastAuthorizeQuery = "";

    private McpServerMapper serverMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private String lastStateJson;
    private McpOAuthService service;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + http.getAddress().getPort();
        http.createContext("/.well-known/oauth-protected-resource", ex ->
                json(ex, 200, "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}"));
        http.createContext("/.well-known/oauth-authorization-server", ex ->
                json(ex, 200, "{\"issuer\":\"" + base + "\","
                        + "\"authorization_endpoint\":\"" + base + "/authorize\","
                        + "\"token_endpoint\":\"" + base + "/token\","
                        + "\"registration_endpoint\":\"" + base + "/register\","
                        + "\"token_endpoint_auth_methods_supported\":[\"none\"],"
                        + "\"code_challenge_methods_supported\":[\"S256\"]}"));
        http.createContext("/register", ex ->
                json(ex, 200, "{\"client_id\":\"mock-client\",\"token_endpoint_auth_method\":\"none\"}"));
        http.createContext("/authorize", ex -> {
            lastAuthorizeQuery = ex.getRequestURI().getQuery();
            json(ex, 200, "{}");
        });
        http.createContext("/token", ex -> {
            refreshCount.incrementAndGet();
            json(ex, 200, "{\"access_token\":\"new-token\",\"refresh_token\":\"r2\","
                    + "\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        });
        http.start();

        serverMapper = mock(McpServerMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new McpOAuthService(serverMapper, cryptoService, redis, objectMapper, mock(AuditService.class));
        ReflectionTestUtils.setField(service, "publicBase", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "frontendBase", "http://localhost:5173");
    }

    @AfterEach
    void tearDown() {
        if (http != null) http.stop(0);
    }

    @Test
    void authorizeBuildsPkceAndRegistersClient() throws Exception {
        McpServer server = oauthServer(null);
        Map<String, Object> result = service.authorizeUrl(server, null);
        String url = String.valueOf(result.get("authorizeUrl"));
        assertTrue(url.startsWith(base + "/authorize?"), url);
        assertTrue(url.contains("code_challenge_method=S256"), url);
        assertTrue(url.contains("client_id=mock-client"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Fmcp-servers%2Foauth%2Fcallback"), url);
        assertEquals("mock-client", result.get("clientId"));

        org.mockito.Mockito.verify(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
        Map<String, Object> stored = objectMapper.readValue(server.getEncryptedAuth() == null ? "{}"
                : cryptoService.decrypt(server.getEncryptedAuth()), Map.class);
        assertEquals("mock-client", stored.get("client_id"));
        assertEquals(base + "/token", stored.get("token_endpoint"));
    }

    @Test
    void stateCarriesVerifierMatchingChallenge() throws Exception {
        McpServer server = oauthServer(null);
        Map<String, Object> result = service.authorizeUrl(server, null);
        String url = String.valueOf(result.get("authorizeUrl"));
        org.mockito.Mockito.verify(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
        String stateJson = captureStateJson();
        JsonNode state = objectMapper.readTree(stateJson);
        String verifier = state.path("verifier").asText();
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(url.contains("code_challenge=" + expected), "PKCE challenge 应为 verifier 的 S256");
    }

    @Test
    void refreshesExpiredTokenAndPersists() throws Exception {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("access_token", "old-token");
        auth.put("refresh_token", "r1");
        auth.put("expires_at", System.currentTimeMillis() - 1000);
        auth.put("token_endpoint", base + "/token");
        auth.put("client_id", "mock-client");
        auth.put("token_endpoint_auth_method", "none");
        McpServer server = oauthServer(auth);

        assertEquals("new-token", service.accessToken(server));
        assertEquals(1, refreshCount.get());
        Map<String, Object> stored = objectMapper.readValue(
                cryptoService.decrypt(server.getEncryptedAuth()), Map.class);
        assertEquals("new-token", stored.get("access_token"));
        assertEquals("r2", stored.get("refresh_token"));

        assertEquals("new-token", service.accessToken(server));
        assertEquals(1, refreshCount.get(), "未过期时不应重复刷新");
    }

    @Test
    void legacyStaticTokenStillWorks() throws Exception {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("token", "legacy-token");
        McpServer server = oauthServer(auth);
        assertEquals("legacy-token", service.accessToken(server));
        assertEquals(0, refreshCount.get());
    }

    private McpServer oauthServer(Map<String, Object> auth) {
        McpServer server = new McpServer();
        server.setId(77L);
        server.setName("oauth-mock");
        server.setTransport("HTTP");
        server.setEndpoint(base + "/mcp");
        server.setAuthType("OAUTH");
        if (auth != null) {
            server.setEncryptedAuth(cryptoService.encrypt(toJson(auth)));
        }
        when(serverMapper.updateById(any(McpServer.class))).thenReturn(1);
        return server;
    }

    private String captureStateJson() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(valueOps).set(anyString(), captor.capture(), any(java.time.Duration.class));
        lastStateJson = captor.getValue();
        return lastStateJson;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void json(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
