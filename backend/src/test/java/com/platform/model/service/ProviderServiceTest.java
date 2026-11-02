package com.platform.model.service;

import com.platform.common.context.TenantContext;
import com.platform.common.security.CryptoService;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.entity.ProviderCredential;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderCredentialMapper;
import com.platform.model.mapper.ProviderMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0：Provider API Key 加密、连通性测试、模型列表同步启停。 */
class ProviderServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void savesCredentialEncryptedAndNeverPlaintext() {
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        ProviderCredentialMapper credentialMapper = mock(ProviderCredentialMapper.class);
        CryptoService cryptoService = mock(CryptoService.class);
        ProviderService service = new ProviderService(providerMapper, mock(ModelMapper.class),
                credentialMapper, cryptoService, mock(Environment.class));
        Provider provider = new Provider();
        provider.setId(1L);
        provider.setTenantId(1L);
        when(providerMapper.findByIdIgnore(1L)).thenReturn(provider);
        when(credentialMapper.selectOne(any())).thenReturn(null);
        when(cryptoService.encrypt("plain-secret")).thenReturn("encrypted-secret");
        TenantContext.setTenantId(1L);

        service.saveCredential(1L, "plain-secret");

        ArgumentCaptor<ProviderCredential> captor = ArgumentCaptor.forClass(ProviderCredential.class);
        verify(credentialMapper).insert(captor.capture());
        assertEquals("encrypted-secret", captor.getValue().getEncryptedContent());
        assertTrue(captor.getValue().getEncryptedContent() != null
                && !captor.getValue().getEncryptedContent().contains("plain-secret"));
        verify(cryptoService).encrypt("plain-secret");
    }

    @Test
    void connectivityTestUsesModelsEndpoint() throws IOException {
        HttpServer server = server(200, "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}");
        try {
            ProviderMapper providerMapper = mock(ProviderMapper.class);
            Provider provider = provider("http://127.0.0.1:" + server.getAddress().getPort());
            when(providerMapper.findByIdIgnore(1L)).thenReturn(provider);
            ProviderService service = new ProviderService(providerMapper, mock(ModelMapper.class),
                    mock(ProviderCredentialMapper.class), mock(CryptoService.class), mock(Environment.class));

            Map<String, Object> result = service.test(1L);

            assertTrue((Boolean) result.get("success"), String.valueOf(result));
            assertTrue(String.valueOf(result.get("message")).contains("可见模型 2 个"), String.valueOf(result));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syncModelsInsertsAndEnablesPulledModels() throws IOException {
        HttpServer server = server(200,
                "{\"data\":[{\"id\":\"m1\",\"name\":\"Model One\"},{\"id\":\"vision-vl\",\"name\":\"Vision\"}]}");
        try {
            ProviderMapper providerMapper = mock(ProviderMapper.class);
            ModelMapper modelMapper = mock(ModelMapper.class);
            Provider provider = provider("http://127.0.0.1:" + server.getAddress().getPort());
            when(providerMapper.findByIdIgnore(1L)).thenReturn(provider);
            when(providerMapper.listVisibleModels(null)).thenReturn(List.of());
            ProviderService service = new ProviderService(providerMapper, modelMapper,
                    mock(ProviderCredentialMapper.class), mock(CryptoService.class), mock(Environment.class));

            Map<String, Object> result = service.syncModels(1L);

            assertEquals(2, result.get("total"));
            assertEquals(2, result.get("enabled"));
            ArgumentCaptor<ModelEntity> captor = ArgumentCaptor.forClass(ModelEntity.class);
            verify(modelMapper, org.mockito.Mockito.times(2)).insertIgnoreTenant(captor.capture());
            assertTrue(captor.getAllValues().stream().allMatch(m -> "ENABLED".equals(m.getStatus())));
            assertTrue(captor.getAllValues().stream().anyMatch(m -> "VISION".equals(m.getPurpose())));
        } finally {
            server.stop(0);
        }
    }

    private Provider provider(String baseUrl) {
        Provider provider = new Provider();
        provider.setId(1L);
        provider.setName("local-mock");
        provider.setProviderType("OPENAI_COMPATIBLE");
        provider.setBaseUrl(baseUrl);
        return provider;
    }

    private HttpServer server(int code, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
