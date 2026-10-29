package com.platform.model.service;

import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModelRouterTest {

    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;
    private ProviderService providerService;
    private ChatModelRouter router;
    private ModelEntity model;

    @BeforeEach
    void setUp() {
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        providerService = mock(ProviderService.class);
        router = new ChatModelRouter(modelMapper, providerMapper, providerService,
                mock(StringRedisTemplate.class));

        model = new ModelEntity();
        model.setId(1L);
        model.setProviderId(9L);
        model.setModelName("mock-model");
        model.setPurpose("CHAT");
        model.setStatus("ENABLED");
        when(modelMapper.findForPricing("mock-model", 1L)).thenReturn(model);
        when(providerService.resolveApiKey(org.mockito.ArgumentMatchers.any(Provider.class)))
                .thenReturn("sk-test");
    }

    @Test
    void unchangedProviderReusesCachedModel() {
        Provider provider = provider(LocalDateTime.of(2026, 10, 25, 10, 0));
        when(providerMapper.findByIdIgnore(9L)).thenReturn(provider);

        ChatModel first = router.resolve(1L, "mock-model");
        ChatModel second = router.resolve(1L, "mock-model");

        assertSame(first, second);
        verify(providerMapper, times(2)).findByIdIgnore(9L);
    }

    @Test
    void providerUpdateRebuildsCachedModel() {
        Provider firstProvider = provider(LocalDateTime.of(2026, 10, 25, 10, 0));
        Provider updatedProvider = provider(LocalDateTime.of(2026, 10, 25, 10, 5));
        when(providerMapper.findByIdIgnore(9L)).thenReturn(firstProvider, updatedProvider);

        ChatModel first = router.resolve(1L, "mock-model");
        ChatModel second = router.resolve(1L, "mock-model");

        assertNotSame(first, second);
    }

    @Test
    void strictResolveDoesNotFallBackToDefaultCloudModel() {
        when(modelMapper.findForPricing("missing-local", 1L)).thenReturn(null);
        when(modelMapper.findDefaultChat(1L)).thenReturn(model);

        var error = assertThrows(com.platform.common.exception.BusinessException.class,
                () -> router.resolveStrict(1L, "missing-local"));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("missing-local"));
        verify(modelMapper, never()).findDefaultChat(1L);
    }

    private Provider provider(LocalDateTime updatedAt) {
        Provider provider = new Provider();
        provider.setId(9L);
        provider.setName("Mock Provider");
        provider.setProviderType("OPENAI_COMPATIBLE");
        provider.setBaseUrl("http://127.0.0.1:18080");
        provider.setStatus("ENABLED");
        provider.setUpdatedAt(updatedAt);
        return provider;
    }
}
