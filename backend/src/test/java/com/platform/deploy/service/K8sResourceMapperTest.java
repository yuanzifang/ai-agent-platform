package com.platform.deploy.service;

import com.platform.deploy.entity.AgentDeployProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sResourceMapperTest {

    private final K8sResourceMapper mapper = new K8sResourceMapper();

    @Test
    void mapsDockerQuantitiesToKubernetesResources() {
        AgentDeployProfile profile = new AgentDeployProfile();
        profile.setCpuLimit("0.5");
        profile.setCpuRequest("250m");
        profile.setMemoryLimit("384m");
        profile.setMemoryRequest("256Mi");

        Map<String, Object> resources = mapper.resources(profile);
        assertEquals(Map.of("cpu", "500m", "memory", "384Mi"), resources.get("limits"));
        assertEquals(Map.of("cpu", "250m", "memory", "256Mi"), resources.get("requests"));
    }

    @Test
    void leavesKubernetesSuffixesIntact() {
        assertEquals("1", mapper.cpu("1", "1"));
        assertEquals("1500m", mapper.cpu("1.5", "1"));
        assertEquals("1Gi", mapper.memory("1g", "512Mi"));
        assertEquals("512Mi", mapper.memory("512m", "512Mi"));
    }
}
