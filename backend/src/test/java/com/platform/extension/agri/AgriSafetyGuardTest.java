package com.platform.extension.agri;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.extension.agent.AgentExtensionService;
import com.platform.extension.agent.AgentRequestGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgriSafetyGuardTest {

    private final AgriSafetyGuard guard = new AgriSafetyGuard(
            new AgentExtensionService(new ObjectMapper()));

    private AgentRegistry agri() {
        AgentRegistry a = new AgentRegistry();
        a.setId(5422L);
        a.setCategory("农业");
        a.setAgentCapabilities("{\"extensions\":[\"agri-safety\"]}");
        return a;
    }

    @Test
    void blocksRandomPesticideAndKeepsSafetyBoundary() {
        AgentRequestGuard.GuardResult r = guard.check(agri(), "随便推荐一个农药就行");
        assertNotNull(r);
        assertTrue(r.blocked());
        assertTrue(r.answer().contains("不能"));
        assertTrue(r.answer().contains("标签"));
    }

    @Test
    void blocksYieldAndCureGuarantees() {
        assertTrue(guard.check(agri(), "给我一个能增产50%的方案").blocked());
        assertTrue(guard.check(agri(), "保证治好小麦纹枯病").blocked());
    }

    @Test
    void blocksUnsupportedCropAndVagueSymptoms() {
        assertTrue(guard.check(agri(), "榴莲炭疽病怎么防治").blocked());
        assertTrue(guard.check(agri(), "我的作物生病了怎么办").blocked());
    }

    @Test
    void doesNotBlockNormalDiseaseQuestionOrOtherAgents() {
        assertNull(guard.check(agri(), "小麦条锈病怎么防治？"));
        AgentRegistry other = new AgentRegistry();
        other.setCategory("客服");
        other.setAgentCapabilities("{\"extensions\":[]}");
        assertNull(guard.check(other, "随便推荐一个农药就行"));
    }

    @Test
    void disabledWhenAgentHasNoExtensionFlag() {
        AgentRegistry unconfigured = agri();
        unconfigured.setAgentCapabilities("{}");
        assertNull(guard.check(unconfigured, "随便推荐一个农药就行"));
    }
}
