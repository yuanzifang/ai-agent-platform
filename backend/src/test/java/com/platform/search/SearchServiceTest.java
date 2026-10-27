package com.platform.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.service.AgentService;
import com.platform.agent.service.CaseService;
import com.platform.capability.service.ToolService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.KnowledgeService;
import com.platform.security.JwtPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

        Map<String, Object> result = new SearchService(agents, kbs, cases, tools).search("客服", principal);

        List<?> list = (List<?>) result.get("list");
        assertEquals(4, list.size());
        assertEquals("AGENT", ((Map<?, ?>) list.get(0)).get("type"));
        assertEquals("/agent/edit/9", ((Map<?, ?>) list.get(0)).get("path"));
        assertEquals("KB", ((Map<?, ?>) list.get(1)).get("type"));
        assertEquals("/knowledge/2", ((Map<?, ?>) list.get(1)).get("path"));
        assertEquals(4, result.get("total"));
        verify(agents).list("客服", null, 1, 5, principal);
    }

    @Test
    void shortQueryDoesNotQueryModules() {
        SearchService service = new SearchService(mock(AgentService.class), mock(KnowledgeService.class),
                mock(CaseService.class), mock(ToolService.class));
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of(), List.of());

        Map<String, Object> result = service.search("客", principal);

        assertEquals(0, result.get("total"));
    }
}
