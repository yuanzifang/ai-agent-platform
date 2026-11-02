package com.platform.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentSkillBindingMapper;
import com.platform.agent.mapper.AgentSubAgentMapper;
import com.platform.agent.mapper.AgentToolBindingMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.billing.service.QuotaService;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.ToolRegistryMapper;
import com.platform.deploy.service.AgentDeploymentService;
import com.platform.eval.service.EvalService;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.model.mapper.ModelMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.mapper.AgentUserGrantMapper;
import com.platform.system.mapper.ResourceProfileMapper;
import com.platform.system.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0：发布版本快照读取与按版本回滚生成新版本。 */
@ExtendWith(MockitoExtension.class)
class AgentServiceVersionTest {

    @Mock private AgentRegistryMapper agentMapper;
    @Mock private AgentKnowledgeBaseMapper agentKbMapper;
    @Mock private AgentSkillBindingMapper agentSkillMapper;
    @Mock private AgentToolBindingMapper agentToolMapper;
    @Mock private AgentSubAgentMapper agentSubAgentMapper;
    @Mock private SkillRegistryMapper skillRegistryMapper;
    @Mock private ToolRegistryMapper toolRegistryMapper;
    @Mock private AgentUserGrantMapper grantMapper;
    @Mock private AgentToolBindingMapper agentToolBindingMapper;
    @Mock private AgentSkillBindingMapper agentSkillBindingMapper;
    @Mock private EvalService evalService;
    @Mock private AuditService auditService;
    @Mock private AgentDeploymentService deploymentService;
    @Mock private AgentVersionMapper versionMapper;
    @Mock private QuotaService quotaService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ModelMapper modelMapper;
    @Mock private ResourceProfileMapper resourceProfileMapper;
    @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

    @InjectMocks private AgentService agentService;

    @Test
    void versionsReturnsPublishedSnapshots() {
        AgentRegistry agent = agent(7L);
        AgentVersion v1 = new AgentVersion();
        v1.setAgentId(7L);
        v1.setVersion("V1");
        when(agentMapper.selectById(7L)).thenReturn(agent);
        when(versionMapper.selectList(any())).thenReturn(List.of(v1));

        List<AgentVersion> versions = agentService.versions(7L);

        assertEquals(1, versions.size());
        assertEquals("V1", versions.get(0).getVersion());
    }

    @Test
    void rollbackRestoresSnapshotAndCreatesNextVersion() {
        AgentRegistry agent = agent(7L);
        AgentVersion v1 = new AgentVersion();
        v1.setAgentId(7L);
        v1.setVersion("V1");
        v1.setSnapshot("{\"name\":\"old-name\",\"systemPrompt\":\"old-prompt\","
                + "\"modelConfig\":{\"modelName\":\"old-model\"},"
                + "\"knowledgeBases\":[],\"skills\":[],\"tools\":[],\"subAgents\":[]}");
        when(agentMapper.selectById(7L)).thenReturn(agent);
        when(versionMapper.selectOne(any())).thenReturn(v1);
        when(versionMapper.countByAgent(7L)).thenReturn(2);

        JwtPrincipal principal = new JwtPrincipal(9L, 1L, false,
                List.of("OWNER"), List.of("agent:edit"));
        Map<String, Object> result = agentService.rollback(7L, "V1", principal);

        assertEquals("V3", result.get("version"));
        assertEquals("V1", result.get("rolledBackFrom"));
        verify(agentMapper).updateById(any(AgentRegistry.class));
        ArgumentCaptor<AgentVersion> inserted = ArgumentCaptor.forClass(AgentVersion.class);
        verify(versionMapper).insert(inserted.capture());
        assertEquals("V3", inserted.getValue().getVersion());
        assertEquals(v1.getSnapshot(), inserted.getValue().getSnapshot());
        verify(auditService).record(9L, "agent.rollback", "agent", "7", "SUCCESS",
                Map.of("from", "V1", "newVersion", "V3"));
    }

    private AgentRegistry agent(Long id) {
        AgentRegistry agent = new AgentRegistry();
        agent.setId(id);
        agent.setTenantId(1L);
        agent.setName("current-name");
        agent.setSystemPrompt("current-prompt");
        agent.setModelConfig("{\"modelName\":\"current-model\"}");
        return agent;
    }
}
