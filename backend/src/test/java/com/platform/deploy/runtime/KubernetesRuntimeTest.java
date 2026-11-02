package com.platform.deploy.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KubernetesRuntimeTest {

    @Test
    void convertsDockerMemoryUnitsToKubernetesQuantity() {
        assertEquals("512Mi", KubernetesRuntime.memoryQuantity("512m"));
        assertEquals("1Gi", KubernetesRuntime.memoryQuantity("1g"));
        assertEquals("512Mi", KubernetesRuntime.memoryQuantity("512Mi"));
        assertEquals("512Mi", KubernetesRuntime.memoryQuantity("512"));
    }

    @Test
    void rendersIngressHostFromTemplate() {
        assertEquals("agent-1.agents.example.com",
                KubernetesRuntime.renderIngressHost("agent-{agentId}.agents.example.com", "aap-agent-1"));
        assertEquals("aap-agent-1.agents.example.com",
                KubernetesRuntime.renderIngressHost("{name}.agents.example.com", "aap-agent-1"));
    }
}
