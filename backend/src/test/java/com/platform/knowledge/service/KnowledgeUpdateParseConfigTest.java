package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.billing.service.QuotaService;
import com.platform.common.ratelimit.RedisRateLimiter;
import com.platform.common.security.NetworkPolicyService;
import com.platform.billing.entity.BillingUsage;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析设置保存（部分更新语义）：只发 parseConfig 不带 name 也必须成功；
 * parseConfig 序列化入列；名称空串/null 保留现值；空对象 = 清除覆盖回跟随默认。
 */
class KnowledgeUpdateParseConfigTest {

    private KnowledgeBaseMapper kbMapper;
    private KnowledgeChunkMapper chunkMapper;
    private BillingUsageMapper billingMapper;
    private PricingService pricingService;
    private EmbeddingService embeddingService;
    private KnowledgePermissionService permissionService;
    private com.platform.knowledge.service.VectorRepository vectorRepository;
    private com.platform.knowledge.service.KnowledgeService service;
    private KnowledgeBase kb;
    private final com.platform.knowledge.service.KnowledgePermissionService.Viewer viewer =
            new com.platform.knowledge.service.KnowledgePermissionService.Viewer(2L, List.of("OWNER"));

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 解析列名需要实体 TableInfo 缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunk.class);

        kbMapper = mock(KnowledgeBaseMapper.class);
        com.platform.knowledge.mapper.KnowledgeDocumentMapper docMapper =
                mock(com.platform.knowledge.mapper.KnowledgeDocumentMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        com.platform.knowledge.mapper.KnowledgeDocumentImageMapper documentImageMapper =
                mock(com.platform.knowledge.mapper.KnowledgeDocumentImageMapper.class);
        billingMapper = mock(BillingUsageMapper.class);
        pricingService = mock(PricingService.class);
        QuotaService quotaService = mock(QuotaService.class);
        com.platform.knowledge.service.StorageService storage =
                mock(com.platform.knowledge.service.StorageService.class);
        embeddingService = mock(EmbeddingService.class);
        vectorRepository = mock(com.platform.knowledge.service.VectorRepository.class);
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
        permissionService = mock(KnowledgePermissionService.class);
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

        kb = new KnowledgeBase();
        kb.setId(161L);
        kb.setTenantId(1L);
        kb.setName("解析验证库");
        kb.setEmbeddingModel("nomic-embed-text");
        when(kbMapper.selectById(161L)).thenReturn(kb);
        when(permissionService.requireEdit(161L, viewer)).thenReturn(kb);
        when(permissionService.requireView(161L, viewer)).thenReturn(kb);
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

    @Test
    void nameOnlyUpdatePreservesDescriptionAndParseConfig() {
        kb.setDescription("原始描述");
        kb.setParseConfig("{\"ocrEnabled\":true}");

        service.update(161L, "新名称", null, null, null, null, viewer());

        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getSqlSet().contains("name"));
        assertFalse(wrapper.getSqlSet().contains("description"),
                "未提交 description 时不应清空原描述");
        assertFalse(wrapper.getSqlSet().contains("parse_config"),
                "未提交 parseConfig 时不应清空原解析配置");
    }

    @Test
    void graphToggleOnlyPreservesDescriptionAndParseConfig() {
        kb.setDescription("原始描述");
        kb.setParseConfig("{\"ocrEnabled\":true}");

        service.update(161L, null, null, null, false, null, viewer());

        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getSqlSet().contains("graph_enabled"));
        assertFalse(wrapper.getSqlSet().contains("description"));
        assertFalse(wrapper.getSqlSet().contains("parse_config"));
    }

    @Test
    void parseConfigOnlyPreservesDescription() {
        kb.setDescription("原始描述");

        service.update(161L, null, null, null, null,
                Map.of("ocrEnabled", true), viewer());

        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getSqlSet().contains("parse_config"));
        assertFalse(wrapper.getSqlSet().contains("description"),
                "只保存解析设置时不应把描述清空");
    }

    @Test
    void explicitBlankDescriptionClearsValue() {
        kb.setDescription("原始描述");

        service.update(161L, null, "   ", null, null, null, viewer());

        LambdaUpdateWrapper<KnowledgeBase> wrapper = capturedWrapper();
        assertTrue(wrapper.getSqlSet().contains("description"));
        assertTrue(wrapper.getParamNameValuePairs().values().stream().anyMatch(Objects::isNull),
                "显式提交空描述应写入 NULL");
    }

    @Test
    void embeddingUsageUsesKnowledgeBaseModelInsteadOfDefault() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(1001L);
        doc.setTenantId(1L);
        doc.setKnowledgeBaseId(161L);
        when(pricingService.cost(anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(BigDecimal.ONE);

        ReflectionTestUtils.invokeMethod(service, "recordEmbeddingUsage",
                doc, 3, "custom-embed");

        ArgumentCaptor<BillingUsage> captor = ArgumentCaptor.forClass(BillingUsage.class);
        verify(billingMapper).insert(captor.capture());
        assertEquals("custom-embed", captor.getValue().getModelName());
        assertEquals(3L, captor.getValue().getInputTokens());
        verify(embeddingService, never()).model();
    }

    @Test
    void chunksExposeChartReviewRequirement() {
        KnowledgeChunk chart = new KnowledgeChunk();
        chart.setId(77L);
        chart.setKnowledgeBaseId(161L);
        chart.setDocumentId(100L);
        chart.setChunkIndex(0);
        chart.setContent("【图表数据·模型识别，需人工核对】{\"series\":[1,2]}");
        Page<KnowledgeChunk> page = new Page<>(1, 10);
        page.setRecords(List.of(chart));
        page.setTotal(1);
        when(chunkMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> result = service.chunks(161L, 100L, 1L, 10L, viewer());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) result.get("list")).get(0);
        assertEquals(Boolean.TRUE, row.get("reviewRequired"));
        assertEquals(null, row.get("reviewStatus"));
    }

    @Test
    void reviewChartChunkPersistsReviewedStatus() {
        KnowledgeChunk chart = new KnowledgeChunk();
        chart.setId(77L);
        chart.setKnowledgeBaseId(161L);
        chart.setContent("【图表数据·模型识别，需人工核对】{\"series\":[1,2]}");
        when(chunkMapper.selectById(77L)).thenReturn(chart);

        Map<String, Object> result = service.updateChunkReview(161L, 77L, true, viewer());

        assertEquals("REVIEWED", result.get("reviewStatus"));
        ArgumentCaptor<Wrapper<KnowledgeChunk>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(chunkMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<KnowledgeChunk> wrapper = (LambdaUpdateWrapper<KnowledgeChunk>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("review_status"));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .anyMatch(value -> "REVIEWED".equals(value)));
    }

    @Test
    void reviewChartChunkCanBeUndoneAndRejectsNormalChunks() {
        KnowledgeChunk chart = new KnowledgeChunk();
        chart.setId(77L);
        chart.setKnowledgeBaseId(161L);
        chart.setContent("【图表数据·模型识别，需人工核对】{}");
        when(chunkMapper.selectById(77L)).thenReturn(chart);

        Map<String, Object> result = service.updateChunkReview(161L, 77L, false, viewer());
        assertEquals("PENDING", result.get("reviewStatus"));

        KnowledgeChunk normal = new KnowledgeChunk();
        normal.setId(78L);
        normal.setKnowledgeBaseId(161L);
        normal.setContent("普通正文分段");
        when(chunkMapper.selectById(78L)).thenReturn(normal);
        com.platform.common.exception.BusinessException error = assertThrows(
                com.platform.common.exception.BusinessException.class,
                () -> service.updateChunkReview(161L, 78L, true, viewer()));
        assertEquals(400, error.getCode());
    }

    @Test
    void correctChartChunkUpdatesContentAndVector() {
        KnowledgeChunk chart = new KnowledgeChunk();
        chart.setId(77L);
        chart.setKnowledgeBaseId(161L);
        chart.setContent("【图表数据·模型识别，需人工核对】{\"series\":[1]}");
        when(chunkMapper.selectById(77L)).thenReturn(chart);
        String corrected = "【图表数据·人工修正】{\"series\":[2]}";
        float[] vector = new float[]{0.1f, 0.2f};
        when(embeddingService.dimension("nomic-embed-text")).thenReturn(2);
        when(embeddingService.embedOne(corrected, "nomic-embed-text")).thenReturn(vector);

        Map<String, Object> result = service.updateChunkReview(161L, 77L, true, corrected, viewer());

        assertEquals(corrected, result.get("content"));
        assertEquals(Boolean.TRUE, result.get("contentChanged"));
        assertEquals("REVIEWED", result.get("reviewStatus"));
        verify(vectorRepository).updateContentForChunk(1L, 161L, 77L, corrected, vector, 2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KnowledgeChunk>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(chunkMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<KnowledgeChunk> wrapper = (LambdaUpdateWrapper<KnowledgeChunk>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("content"));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .anyMatch(value -> corrected.equals(value)));
    }

    @Test
    void chunksPageIsClamped() {
        Page<KnowledgeChunk> page = new Page<>(1, 10);
        when(chunkMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> result = service.chunks(161L, 100L, 0L, 1000L, viewer());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<KnowledgeChunk>> captor = ArgumentCaptor.forClass(Page.class);
        verify(chunkMapper).selectPage(captor.capture(), any());
        assertEquals(1L, captor.getValue().getCurrent());
        assertEquals(100L, captor.getValue().getSize());
        assertEquals(1L, result.get("page"));
        assertEquals(100L, result.get("pageSize"));
    }

}
