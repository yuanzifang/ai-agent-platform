package com.platform.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.service.AgentService;
import com.platform.agent.service.CaseService;
import com.platform.capability.service.ToolService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.KnowledgeService;
import com.platform.security.JwtPrincipal;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    @Test
    void aggregatesAndRanksAcrossModules() {
        AgentService agents = mock(AgentService.class);
        KnowledgeService kbs = mock(KnowledgeService.class);
        CaseService cases = mock(CaseService.class);
        ToolService tools = mock(ToolService.class);
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of("OWNER"), List.of());
        AgentRegistry agent = new AgentRegistry();
        agent.setId(9L);
        agent.setName("客服助手");
        agent.setDescription("退款政策");
        Page<AgentRegistry> page = new Page<>(1, 5);
        page.setRecords(List.of(agent));
        when(agents.list(eq("客服"), any(), eq(1L), eq(5L), eq(principal))).thenReturn(page);
        when(kbs.list(eq("客服"), any(), eq(1L), eq(5L), any(KnowledgePermissionService.Viewer.class)))
                .thenReturn(Map.of("list", List.of(Map.of("id", 2L, "name", "客服知识库",
                        "description", "退款政策"))));
        when(cases.list(eq(principal), any(), any(), any(), eq("客服"), any()))
                .thenReturn(Map.of("list", List.of(Map.of("question", "客服怎么申请退款",
                        "expectedAnswer", "说明条件"))));
        when(tools.list(eq("客服"), any(), any()))
                .thenReturn(Map.of("list", List.of(Map.of("name", "工单工具",
                        "description", "客服工单"))));

        Map<String, Object> result = new SearchService(agents, kbs, cases, tools, mock(StringRedisTemplate.class)).search("客服", principal);

        List<?> list = (List<?>) result.get("list");
        assertEquals(4, list.size());
        assertEquals("AGENT", ((Map<?, ?>) list.get(0)).get("type"));
        assertEquals("/agent/edit/9", ((Map<?, ?>) list.get(0)).get("path"));
        assertEquals("<mark>客服</mark>助手", ((Map<?, ?>) list.get(0)).get("nameHtml"));
        assertEquals("KB", ((Map<?, ?>) list.get(1)).get("type"));
        assertEquals("/knowledge/2", ((Map<?, ?>) list.get(1)).get("path"));
        assertEquals(List.of("客服"), result.get("tokens"));
        assertEquals(4, result.get("total"));
        verify(agents).list("客服", null, 1, 5, principal);
    }

    @Test
    void segmentsChineseQueryAndEscapesUserContent() {
        AgentService agents = mock(AgentService.class);
        KnowledgeService kbs = mock(KnowledgeService.class);
        CaseService cases = mock(CaseService.class);
        ToolService tools = mock(ToolService.class);
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of("OWNER"), List.of());
        AgentRegistry agent = new AgentRegistry();
        agent.setId(10L);
        agent.setName("客服退款助手");
        agent.setDescription("<script>客服退款</script>");
        Page<AgentRegistry> page = new Page<>(1, 5);
        page.setRecords(List.of(agent));
        when(agents.list(eq("客服退款"), any(), eq(1L), eq(5L), eq(principal))).thenReturn(page);
        when(kbs.list(eq("客服退款"), any(), eq(1L), eq(5L), any(KnowledgePermissionService.Viewer.class)))
                .thenReturn(Map.of("list", List.of()));
        when(cases.list(eq(principal), any(), any(), any(), eq("客服退款"), any()))
                .thenReturn(Map.of("list", List.of()));
        when(tools.list(eq("客服退款"), any(), any())).thenReturn(Map.of("list", List.of()));

        Map<String, Object> result = new SearchService(agents, kbs, cases, tools, mock(StringRedisTemplate.class)).search("客服退款", principal);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("list")).get(0);

        assertEquals(List.of("客服", "退款"), result.get("tokens"));
        assertEquals("<mark>客服退款</mark>助手", row.get("nameHtml"));
        assertEquals("&lt;script&gt;<mark>客服退款</mark>&lt;/script&gt;", row.get("descHtml"));
        assertEquals(true, (Integer) row.get("score") > 0);
    }

    @Test
    void suggestsClosestVisibleNameWhenNoResults() {
        AgentService agents = mock(AgentService.class);
        KnowledgeService kbs = mock(KnowledgeService.class);
        CaseService cases = mock(CaseService.class);
        ToolService tools = mock(ToolService.class);
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of("OWNER"), List.of());
        Page<AgentRegistry> none = new Page<>(1, 5);
        none.setRecords(List.of());
        Page<AgentRegistry> all = new Page<>(1, 100);
        AgentRegistry candidate = new AgentRegistry();
        candidate.setId(1L);
        candidate.setName("客服助手");
        all.setRecords(List.of(candidate));
        when(agents.list(eq("客服住手"), any(), eq(1L), eq(5L), eq(principal))).thenReturn(none);
        when(agents.list(eq(""), any(), eq(1L), eq(100L), eq(principal))).thenReturn(all);
        when(kbs.list(any(), any(), anyLong(), anyLong(), any(KnowledgePermissionService.Viewer.class)))
                .thenReturn(Map.of("list", List.of()));
        when(cases.list(eq(principal), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("list", List.of()));
        when(tools.list(any(), any(), any())).thenReturn(Map.of("list", List.of()));

        Map<String, Object> result = new SearchService(agents, kbs, cases, tools,
                mock(StringRedisTemplate.class)).search("客服住手", principal);

        assertEquals("客服助手", result.get("suggestion"));
    }

    @Test
    void feedbackIncrementsTenantResultCounter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        JwtPrincipal principal = new JwtPrincipal(1L, 7L, false, List.of("OWNER"), List.of());
        SearchService service = new SearchService(mock(AgentService.class), mock(KnowledgeService.class),
                mock(CaseService.class), mock(ToolService.class), redis);

        service.feedback("AGENT", 9L, "客服", principal);

        verify(values).increment("search:click:7:AGENT:9");
        verify(redis).expire("search:click:7:AGENT:9", Duration.ofDays(30));
    }

    @Test
    void shortQueryDoesNotQueryModules() {
        SearchService service = new SearchService(mock(AgentService.class), mock(KnowledgeService.class),
                mock(CaseService.class), mock(ToolService.class), mock(StringRedisTemplate.class));
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of(), List.of());

        Map<String, Object> result = service.search("客", principal);

        assertEquals(0, result.get("total"));
    }
}
