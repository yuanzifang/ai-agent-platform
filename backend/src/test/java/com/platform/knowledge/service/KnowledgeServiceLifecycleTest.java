package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.billing.service.QuotaService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.common.security.NetworkPolicyService;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeDocumentImage;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentImageMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 知识库生命周期：默认图谱关闭、可见性过滤、分页上限、删除引用确认。 */
class KnowledgeServiceLifecycleTest {

    private KnowledgeBaseMapper kbMapper;
    private KnowledgeDocumentMapper docMapper;
    private KnowledgeChunkMapper chunkMapper;
    private KnowledgeDocumentImageMapper imageMapper;
    private EmbeddingService embeddingService;
    private VectorRepository vectorRepository;
    private StorageService storage;
    private KnowledgeGraphService graphService;
    private DocumentParser parser;
    private KnowledgePermissionService permissionService;
    private SysUserMapper userMapper;
    private AgentKnowledgeBaseMapper agentKbMapper;
    private AgentRegistryMapper agentRegistryMapper;
    private AuditService auditService;
    private CryptoService cryptoService;
    private KnowledgeService service;
    private KnowledgeBase kb;
    private HttpServer httpServer;
    private final KnowledgePermissionService.Viewer viewer =
            new KnowledgePermissionService.Viewer(7L, List.of("OWNER"));

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(9L);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeChunk.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentImage.class);

        kbMapper = mock(KnowledgeBaseMapper.class);
        docMapper = mock(KnowledgeDocumentMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        imageMapper = mock(KnowledgeDocumentImageMapper.class);
        embeddingService = mock(EmbeddingService.class);
        vectorRepository = mock(VectorRepository.class);
        storage = mock(StorageService.class);
        graphService = mock(KnowledgeGraphService.class);
        parser = mock(DocumentParser.class);
        permissionService = mock(KnowledgePermissionService.class);
        userMapper = mock(SysUserMapper.class);
        agentKbMapper = mock(AgentKnowledgeBaseMapper.class);
        agentRegistryMapper = mock(AgentRegistryMapper.class);
        auditService = mock(AuditService.class);
        cryptoService = mock(CryptoService.class);
        KnowledgeParseConfigResolver resolver = mock(KnowledgeParseConfigResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(ParseOptions.defaults(
                false, "tesseract", "chi_sim+eng", 150, 20, false, "", "", 3));

        service = new KnowledgeService(kbMapper, docMapper, chunkMapper, imageMapper,
                mock(BillingUsageMapper.class), mock(PricingService.class), mock(QuotaService.class),
                storage, embeddingService, vectorRepository, mock(HybridRetrievalService.class),
                parser, resolver, mock(ImageUnderstandingService.class),
                graphService, new ObjectMapper(), permissionService, userMapper,
                agentKbMapper, agentRegistryMapper, auditService, mock(NetworkPolicyService.class),
                cryptoService);

        TenantContext.setTenantId(9L);
        kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(9L);
        kb.setCreatedBy(7L);
        kb.setName("产品资料");
        kb.setEmbeddingModel("default-embed");
        kb.setGraphEnabled(true);
        when(kbMapper.selectById(1L)).thenReturn(kb);
        when(permissionService.requireEdit(1L, viewer)).thenReturn(kb);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) httpServer.stop(0);
        TenantContext.clear();
    }

    @Test
    void newKnowledgeBaseDefaultsGraphOffAndUsesDefaultEmbeddingModel() {
        when(embeddingService.model()).thenReturn("default-embed");
        when(embeddingService.supports("default-embed")).thenReturn(true);
        doAnswer(invocation -> {
            KnowledgeBase row = invocation.getArgument(0);
            row.setId(11L);
            return 1;
        }).when(kbMapper).insert(any(KnowledgeBase.class));

        Map<String, Object> result = service.create("新库", "描述", null, null, null, 7L);

        assertEquals(11L, result.get("id"));
        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(kbMapper).insert(captor.capture());
        assertFalse(captor.getValue().getGraphEnabled());
        assertEquals("TENANT", captor.getValue().getVisibility());
        assertEquals("default-embed", captor.getValue().getEmbeddingModel());
        assertEquals(7L, captor.getValue().getCreatedBy());
        verify(vectorRepository).ensureTenantSchema(9L);
    }

    @Test
    void createRejectsUnsupportedEmbeddingModelBeforeInsert() {
        when(embeddingService.supports("unknown")).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("新库", null, "PRIVATE", "unknown", true, 7L));

        assertEquals(400, error.getCode());
        verify(kbMapper, never()).insert(any(KnowledgeBase.class));
    }

    @Test
    void callbackPersistsContentValidityRange() {
        ReflectionTestUtils.setField(service, "documentParseExecutor", mock(ThreadPoolTaskExecutor.class));
        when(storage.exists("9/knowledge/1/abc/file.pdf")).thenReturn(true);
        when(storage.statSize("9/knowledge/1/abc/file.pdf")).thenReturn(12L);
        doAnswer(invocation -> {
            KnowledgeDocument doc = invocation.getArgument(0);
            doc.setId(55L);
            return 1;
        }).when(docMapper).insert(any(KnowledgeDocument.class));
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-31T23:59:59Z");

        service.callback(1L, "9/knowledge/1/abc/file.pdf", null, null, from, to, viewer);

        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(docMapper).insert(captor.capture());
        assertEquals(LocalDateTime.ofInstant(from, ZoneId.systemDefault()),
                captor.getValue().getEffectiveFrom());
        assertEquals(LocalDateTime.ofInstant(to, ZoneId.systemDefault()),
                captor.getValue().getEffectiveTo());
    }

    @Test
    void parseUsesDocumentValidityFallbackForVectorRows() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(55L);
        doc.setKnowledgeBaseId(1L);
        doc.setTenantId(9L);
        doc.setObjectName("obj");
        doc.setFileName("a.pdf");
        doc.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        when(storage.get("obj")).thenReturn("pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(parser.parseWithParents(anyString(), any(), any()))
                .thenReturn(List.of(new DocumentParser.ParsedChunk("hello", 0, "hello")));
        when(parser.extractImages(anyString(), any())).thenReturn(List.of());
        when(permissionService.aclOf(kb)).thenReturn(
                new KnowledgePermissionService.Acl(List.of(), true));
        when(embeddingService.dimension("default-embed")).thenReturn(2);
        when(embeddingService.embedBatch(anyList(), anyString()))
                .thenReturn(new float[][]{{0.1f, 0.2f}});
        doAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            chunk.setId(77L);
            return 1;
        }).when(chunkMapper).insert(any(KnowledgeChunk.class));

        ReflectionTestUtils.invokeMethod(service, "parseOnce", doc);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(vectorRepository).saveBatch(eq(9L), rows.capture(), eq(2));
        Map<String, Object> row = rows.getValue().get(0);
        assertEquals(java.sql.Timestamp.valueOf(doc.getCreatedAt()), row.get("effective_from"));
        assertEquals(null, row.get("effective_to"));
        assertTrue(String.valueOf(row.get("metadata")).contains(
                "\"effective_from\":\"2026-01-01T10:00\""));
    }

    @Test
    void chartChunkCompactsWithoutBreakingJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode chart = mapper.createObjectNode();
        chart.put("isChart", true);
        chart.put("title", "Very long chart ".repeat(30));
        ArrayNode series = chart.putArray("series");
        for (int s = 0; s < 8; s++) {
            ObjectNode item = series.addObject();
            item.put("name", "Series " + s);
            ArrayNode points = item.putArray("points");
            for (int p = 0; p < 200; p++) {
                ObjectNode point = points.addObject();
                point.put("x", "Label " + p);
                point.put("y", p * 1.5);
            }
        }
        java.util.List<String> warnings = new java.util.ArrayList<>();
        String text = ReflectionTestUtils.invokeMethod(service, "buildChartChunk", chart, warnings);
        assertTrue(text.length() <= 4000, "图表块应受长度约束，实际=" + text.length());
        assertEquals(null, ChartDataValidator.validate(text), "压缩后仍必须是合法图表 JSON");
        assertFalse(text.contains("..."), "不应使用字符截断破坏 JSON");
        assertTrue(text.contains("truncatedPoints") || text.contains("compacted")
                || text.contains("omittedSeries"), "应有压缩标记");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("图表数据过长")), "应记录解析告警");
    }

    @Test
    void reusesChunksAndVectorsAcrossKnowledgeBasesForSameFileHash() throws Exception {
        byte[] bytes = "same-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(56L);
        doc.setKnowledgeBaseId(1L);
        doc.setTenantId(9L);
        doc.setObjectName("obj-copy");
        doc.setFileName("copy.pdf");
        doc.setCreatedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        KnowledgeDocument source = new KnowledgeDocument();
        source.setId(44L);
        source.setKnowledgeBaseId(2L);
        source.setTenantId(9L);
        source.setFileName("original.pdf");
        source.setStatus("DONE");
        source.setSourceSha256("any");
        ParseOptions parseOptions = ParseOptions.defaults(
                false, "tesseract", "chi_sim+eng", 150, 20, false, "", "", 3);
        source.setParseFingerprint(ReflectionTestUtils.invokeMethod(
                service, "parseFingerprint", parseOptions));
        when(storage.get("obj-copy")).thenReturn(bytes);
        when(docMapper.selectList(any())).thenReturn(List.of(source));
        when(permissionService.aclOf(kb)).thenReturn(
                new KnowledgePermissionService.Acl(List.of(), true));
        when(embeddingService.dimension("default-embed")).thenReturn(2);
        when(parser.extractImages(anyString(), any())).thenReturn(List.of());
        KnowledgeChunk old = new KnowledgeChunk();
        old.setId(70L);
        old.setDocumentId(44L);
        old.setKnowledgeBaseId(1L);
        old.setChunkIndex(0);
        old.setParentIndex(3);
        old.setParentContent("父块");
        old.setContent("复用正文");
        old.setPageFrom(1);
        old.setPageTo(1);
        when(chunkMapper.selectList(any())).thenReturn(List.of(old));
        doAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            chunk.setId(88L);
            return 1;
        }).when(chunkMapper).insert(any(KnowledgeChunk.class));
        when(vectorRepository.copyDocumentVectors(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), any(), any())).thenReturn(1);

        ReflectionTestUtils.invokeMethod(service, "parseOnce", doc);

        verify(parser, never()).parseWithParentsAndWarnings(anyString(), any(), any());
        verify(vectorRepository).copyDocumentVectors(eq(9L), eq(44L), eq(56L), eq(2),
                eq("default-embed"), any(), any());
        verify(chunkMapper, atLeastOnce()).insert(any(KnowledgeChunk.class));
    }

    @Test
    void recoversStaleParsingDocumentAndCleansPartialData() {
        KnowledgeDocument stale = new KnowledgeDocument();
        stale.setId(78L);
        stale.setKnowledgeBaseId(1L);
        stale.setTenantId(9L);
        stale.setStatus("PARSING");
        stale.setParseStartedAt(LocalDateTime.now().minusHours(7));
        when(docMapper.selectList(any())).thenReturn(List.of(stale));
        when(docMapper.update(isNull(), any())).thenReturn(1);

        int recovered = service.recoverStaleParsing(360, 20);

        assertEquals(1, recovered);
        verify(chunkMapper).delete(any());
        verify(vectorRepository).deleteByDocument(9L, 78L);
        verify(graphService).deleteByDocument(1L, 78L);
    }

    @Test
    void backfillsSourceHashForLegacyDocument() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(77L);
        doc.setKnowledgeBaseId(1L);
        doc.setObjectName("legacy-obj");
        doc.setFileName("legacy.pdf");
        doc.setStatus("DONE");
        when(docMapper.selectList(any())).thenReturn(List.of(doc));
        when(storage.get("legacy-obj")).thenReturn("legacy-bytes".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));

        Map<String, Object> result = service.backfillSourceHashes(1L, 20, viewer);

        assertEquals(1, result.get("updated"));
        assertEquals(1, result.get("scanned"));
        verify(docMapper).update(isNull(), any());
    }

    @Test
    void updateDocumentValiditySyncsDocumentAndVectorRows() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(55L);
        doc.setKnowledgeBaseId(1L);
        doc.setTenantId(9L);
        doc.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        when(docMapper.selectById(55L)).thenReturn(doc);
        Instant from = Instant.parse("2026-02-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");

        Map<String, Object> result = service.updateDocumentValidity(1L, 55L, from, to, viewer);

        LocalDateTime expectedFrom = LocalDateTime.ofInstant(from, ZoneId.systemDefault());
        LocalDateTime expectedTo = LocalDateTime.ofInstant(to, ZoneId.systemDefault());
        assertEquals(expectedFrom, result.get("effectiveFrom"));
        assertEquals(expectedTo, result.get("effectiveTo"));
        ArgumentCaptor<Wrapper<KnowledgeDocument>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(docMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<KnowledgeDocument> wrapper =
                (LambdaUpdateWrapper<KnowledgeDocument>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("effective_from"));
        assertTrue(wrapper.getSqlSet().contains("effective_to"));
        verify(vectorRepository).updateValidityForDocument(9L, 1L, 55L,
                java.sql.Timestamp.valueOf(expectedFrom), java.sql.Timestamp.valueOf(expectedTo));
    }

    @Test
    void rebuildValidityUsesDocumentDatesForAllVectors() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(55L);
        doc.setKnowledgeBaseId(1L);
        doc.setTenantId(9L);
        doc.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        doc.setEffectiveTo(LocalDateTime.of(2026, 12, 31, 23, 59));
        when(docMapper.selectList(any())).thenReturn(List.of(doc));

        Map<String, Object> result = service.rebuildValidity(1L, viewer);

        assertEquals(1, result.get("documents"));
        verify(vectorRepository).updateValidityForDocument(9L, 1L, 55L,
                java.sql.Timestamp.valueOf(doc.getCreatedAt()),
                java.sql.Timestamp.valueOf(doc.getEffectiveTo()));
    }

    @Test
    void setPdfPasswordStoresCiphertextBeforeReparse() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(55L);
        doc.setKnowledgeBaseId(1L);
        doc.setTenantId(9L);
        doc.setFileName("scan.pdf");
        doc.setStatus("FAILED");
        doc.setParseConfig("{}");
        when(docMapper.selectById(55L)).thenReturn(doc);
        when(imageMapper.selectList(any())).thenReturn(List.of());
        when(cryptoService.encrypt("secret")).thenReturn("v1:encrypted");

        service.setPdfPasswordAndReparse(1L, 55L, "secret", viewer);

        ArgumentCaptor<Wrapper<KnowledgeDocument>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(docMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<KnowledgeDocument> wrapper =
                (LambdaUpdateWrapper<KnowledgeDocument>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("parse_config"));
        String stored = String.valueOf(wrapper.getParamNameValuePairs().values());
        assertTrue(stored.contains("v1:encrypted"));
        assertFalse(stored.contains("secret"));
    }

    @Test
    void listFiltersInvisibleKnowledgeBasesAndCapsPageSize() {
        KnowledgeBase hidden = new KnowledgeBase();
        hidden.setId(2L);
        hidden.setTenantId(9L);
        hidden.setCreatedBy(8L);
        hidden.setName("私有库");
        when(kbMapper.selectList(any())).thenReturn(List.of(kb, hidden));
        when(permissionService.canView(kb, viewer)).thenReturn(true);
        when(permissionService.canView(hidden, viewer)).thenReturn(false);
        when(docMapper.statsByKb()).thenReturn(List.of(
                Map.of("kbId", 1L, "documentCount", 2L, "chunkCount", 8L)));
        SysUser creator = new SysUser();
        creator.setId(7L);
        creator.setUsername("alice");
        creator.setDisplayName("Alice");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(creator));

        Map<String, Object> result = service.list(null, null, 1L, 1000L, viewer);

        assertEquals(1L, result.get("total"));
        assertEquals(100L, result.get("pageSize"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("list");
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("id"));
        assertEquals("Alice", rows.get(0).get("creatorName"));
        assertEquals(2L, rows.get(0).get("documentCount"));
    }

    @Test
    void hugePageDoesNotOverflowOrThrow() {
        when(kbMapper.selectList(any())).thenReturn(List.of(kb));
        when(permissionService.canView(kb, viewer)).thenReturn(true);
        when(docMapper.statsByKb()).thenReturn(List.of());
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        Map<String, Object> result = service.list(null, null, Long.MAX_VALUE, 100L, viewer);

        assertEquals(1L, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("list");
        assertTrue(rows.isEmpty());
    }

    @Test
    void deleteRequiresForceWhenReferencedByLiveAgent() {
        AgentKnowledgeBase binding = new AgentKnowledgeBase();
        binding.setKnowledgeBaseId(1L);
        binding.setAgentId(100L);
        when(agentKbMapper.selectList(any())).thenReturn(List.of(binding));
        AgentRegistry agent = new AgentRegistry();
        agent.setId(100L);
        agent.setName("客服助手");
        agent.setStatus("PUBLISHED");
        when(agentRegistryMapper.selectBatchIds(any())).thenReturn(List.of(agent));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.delete(1L, false, viewer));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("客服助手"));
        verify(kbMapper, never()).deleteById(1L);
    }

    @Test
    void importUrlRejectsChunkedOversizeBeforeBufferingWholeBody() throws Exception {
        ReflectionTestUtils.setField(service, "importMaxBytes", 64);
        byte[] oversized = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
        String url = serve(oversized, 0, "text/plain");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.importUrl(1L, url, viewer));

        assertEquals(413, error.getCode());
        verifyNoInteractions(storage);
    }

    @Test
    void importUrlRejectsOversizedContentLength() throws Exception {
        ReflectionTestUtils.setField(service, "importMaxBytes", 64);
        String url = serve(new byte[4096], 4096, "text/plain");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.importUrl(1L, url, viewer));

        assertEquals(413, error.getCode());
        verifyNoInteractions(storage);
    }

    @Test
    void importUrlPersistsContentValidity() throws Exception {
        ReflectionTestUtils.setField(service, "importMaxBytes", 64);
        byte[] payload = "hello validity".getBytes(StandardCharsets.UTF_8);
        String url = serve(payload, payload.length, "text/plain");
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.statSize(anyString())).thenReturn((long) payload.length);
        doAnswer(invocation -> {
            KnowledgeDocument row = invocation.getArgument(0);
            row.setId(124L);
            return 1;
        }).when(docMapper).insert(any(KnowledgeDocument.class));
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        service.importUrl(1L, url, from, to, viewer);

        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(docMapper).insert(captor.capture());
        assertEquals(LocalDateTime.ofInstant(from, ZoneId.systemDefault()),
                captor.getValue().getEffectiveFrom());
        assertEquals(LocalDateTime.ofInstant(to, ZoneId.systemDefault()),
                captor.getValue().getEffectiveTo());
    }

    @Test
    void importUrlStreamsAndStoresPayloadWithinLimit() throws Exception {
        ReflectionTestUtils.setField(service, "importMaxBytes", 64);
        byte[] payload = "hello url import".getBytes(StandardCharsets.UTF_8);
        String url = serve(payload, payload.length, "text/plain");
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.statSize(anyString())).thenReturn((long) payload.length);
        doAnswer(invocation -> {
            KnowledgeDocument row = invocation.getArgument(0);
            row.setId(123L);
            return 1;
        }).when(docMapper).insert(any(KnowledgeDocument.class));

        Map<String, Object> result = service.importUrl(1L, url, viewer);

        assertEquals(123L, result.get("documentId"));
        assertEquals(url, result.get("sourceUrl"));
        assertEquals("payload.txt", result.get("fileName"));
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(anyString(), body.capture(), eq("text/plain"));
        assertArrayEquals(payload, body.getValue());
    }

    private String serve(byte[] payload, long declaredLength, String contentType) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/payload", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            try {
                exchange.sendResponseHeaders(200, declaredLength);
                if (payload.length > 0) {
                    exchange.getResponseBody().write(payload);
                }
            } catch (Exception ignored) {
                // 客户端在读取上限后主动断开时服务端写失败属于预期行为
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/payload";
    }

    @Test
    void forcedDeleteCascadesAllRelatedData() {
        when(agentKbMapper.selectList(any())).thenReturn(List.of());
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(10L);
        doc.setKnowledgeBaseId(1L);
        doc.setTenantId(9L);
        doc.setObjectName("9/knowledge/1/doc.pdf");
        when(docMapper.selectList(any())).thenReturn(List.of(doc));
        when(imageMapper.selectList(any())).thenReturn(List.of());

        service.delete(1L, true, viewer);

        verify(storage).remove("9/knowledge/1/doc.pdf");
        verify(vectorRepository).deleteByKnowledgeBase(9L, 1L);
        verify(graphService).deleteByKnowledgeBase(1L);
        verify(chunkMapper).delete(any());
        verify(docMapper).delete(any());
        verify(permissionService).deleteAll(1L);
        verify(kbMapper).deleteById(1L);
        verify(auditService).record(7L, "kb.delete", "knowledge", "1", "SUCCESS",
                Map.of("name", "产品资料", "documents", 1, "forced", true));
    }
}
