package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.billing.service.QuotaService;
import com.platform.common.ratelimit.RedisRateLimiter;
import com.platform.common.security.NetworkPolicyService;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentImageMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.model.service.ChatModelRouter;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析设置保存（部分更新语义）：只发 parseConfig 不带 name 也必须成功；
 * parseConfig 序列化入列；名称空串/null 保留现值；空对象 = 清除覆盖回跟随默认。
 */
class KnowledgeUpdateParseConfigTest {

    private KnowledgeBaseMapper kbMapper;
    private com.platform.knowledge.service.KnowledgeService service;
    private KnowledgeBase kb;
    private final com.platform.knowledge.service.KnowledgePermissionService.Viewer viewer =
            new com.platform.knowledge.service.KnowledgePermissionService.Viewer(2L, List.of("OWNER"));

    @BeforeEach
    void setUp() {
        kbMapper = mock(KnowledgeBaseMapper.class);
        com.platform.knowledge.mapper.KnowledgeDocumentMapper docMapper =
                mock(com.platform.knowledge.mapper.KnowledgeDocumentMapper.class);
        com.platform.knowledge.mapper.KnowledgeChunkMapper chunkMapper =
                mock(com.platform.knowledge.mapper.KnowledgeChunkMapper.class);
        com.platform.knowledge.mapper.KnowledgeDocumentImageMapper documentImageMapper =
                mock(com.platform.knowledge.mapper.KnowledgeDocumentImageMapper.class);
        BillingUsageMapper billingMapper = mock(BillingUsageMapper.class);
        PricingService pricingService = mock(PricingService.class);
        QuotaService quotaService = mock(QuotaService.class);
        com.platform.knowledge.service.StorageService storage =
                mock(com.platform.knowledge.service.StorageService.class);
        com.platform.knowledge.service.EmbeddingService embeddingService =
                mock(com.platform.knowledge.service.EmbeddingService.class);
        com.platform.knowledge.service.VectorRepository vectorRepository =
                mock(com.platform.knowledge.service.VectorRepository.class);
        com.platform.knowledge.service.HybridRetrievalService hybridRetrieval =
                mock(com.platform.knowledge.service.HybridRetrievalService.class);
        com.platform.knowledge.service.DocumentParser parser =
                mock(com.platform.knowledge.service.DocumentParser.class);
        com.platform.knowledge.service.KnowledgeParseConfigResolver parseConfigResolver =
                mock(com.platform.knowledge.service.KnowledgeParseConfigResolver.class);
        when(parseConfigResolver.resolve(any(), any())).thenReturn(
                com.platform.knowledge.service.ParseOptions.defaults(
                        false, "tesseract", "chi_sim+eng", 150, 20, false, "", "", 3));
        com.platform.knowledge.service.ImageUnderstandingService imageUnderstanding =
                mock(com.platform.knowledge.service.ImageUnderstandingService.class);
        com.platform.knowledge.service.KnowledgeGraphService graphService =
                mock(com.platform.knowledge.service.KnowledgeGraphService.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.platform.knowledge.service.KnowledgePermissionService permissionService =
                mock(com.platform.knowledge.service.KnowledgePermissionService.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        AgentKnowledgeBaseMapper agentKbMapper = mock(AgentKnowledgeBaseMapper.class);
        AgentRegistryMapper agentRegistryMapper = mock(AgentRegistryMapper.class);
        AuditService auditService = mock(AuditService.class);
        NetworkPolicyService networkPolicy = mock(NetworkPolicyService.class);

        service = new com.platform.knowledge.service.KnowledgeService(
                kbMapper, docMapper, chunkMapper, documentImageMapper,
                billingMapper, pricingService, quotaService, storage, embeddingService,
                vectorRepository, hybridRetrieval, parser, parseConfigResolver, imageUnderstanding,
                graphService, objectMapper, permissionService, userMapper, agentKbMapper,
                agentRegistryMapper, auditService, networkPolicy);

        viewer = new com.platform.knowledge.service.KnowledgePermissionService.Viewer(2L, List.of("OWNER"));
        kb = new KnowledgeBase();
        kb.setId(161L);
        kb.setTenantId(1L);
        kb.setName("解析验证库");
        kb.setEmbeddingModel("nomic-embed-text");
        when(kbMapper.selectById(161L)).thenReturn(kb);
        when(permissionService.requireEdit(161L, viewer)).thenReturn(kb);
    }



    private com.platform.knowledge.service.KnowledgePermissionService.Viewer viewer() {
        return new com.platform.knowledge.service.KnowledgePermissionService.Viewer(2L, List.of("OWNER"));
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<KnowledgeBase> capturedWrapper() {
        ArgumentCaptor<Wrapper<KnowledgeBase>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(kbMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<KnowledgeBase>) captor.getValue();
    }

    @Test
    void parseConfigOnlySavePersistsJsonAndKeepsName() {
        service.update(161L, null, null, null, null,
                Map.of("ocrEnabled", true, "ocrLanguages", "chi_sim+eng"), viewer());

        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getSqlSet().contains("parse_config"));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .anyMatch(v -> String.valueOf(v).contains("ocrEnabled")));
        // 名称保留现值（部分更新语义）
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .anyMatch(v -> "解析验证库".equals(v)));
    }

    @Test
    void emptyObjectClearsOverride() {
        service.update(161L, null, null, null, null, Map.of(), viewer());
        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getSqlSet().contains("parse_config"));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .noneMatch(v -> String.valueOf(v).contains("ocrEnabled")),
                "空对象不应带入任何自定义解析键");
    }

    @Test
    void blankNameKeepsExisting() {
        service.update(161L, "  ", null, null, null, null, viewer());
        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .anyMatch(v -> "解析验证库".equals(v)));
    }

    @Test
    void invalidJsonConfigRejected() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("x", new Object());                                      // 不可序列化
        com.platform.common.exception.BusinessException e =
                assertThrows(com.platform.common.exception.BusinessException.class,
                        () -> service.update(161L, null, null, null, null, bad, viewer()));
        assertEquals(400, e.getCode());
    }
}
