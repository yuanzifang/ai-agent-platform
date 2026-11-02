package com.platform.deploy.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeConfigTest {

    @Test
    void explicitDockerWins() {
        DockerCliRuntime docker = mock(DockerCliRuntime.class);
        KubernetesRuntime kubernetes = mock(KubernetesRuntime.class);
        assertSame(docker, new AgentRuntimeConfig().containerRuntime(docker, kubernetes, "docker"));
    }

    @Test
    void explicitKubernetesWins() {
        DockerCliRuntime docker = mock(DockerCliRuntime.class);
        KubernetesRuntime kubernetes = mock(KubernetesRuntime.class);
        assertSame(kubernetes, new AgentRuntimeConfig().containerRuntime(docker, kubernetes, "kubernetes"));
    }

    @Test
    void autoUsesKubernetesWhenClusterIsDetected() {
        DockerCliRuntime docker = mock(DockerCliRuntime.class);
        KubernetesRuntime kubernetes = mock(KubernetesRuntime.class);
        when(kubernetes.available()).thenReturn(true);
        assertSame(kubernetes, new AgentRuntimeConfig().containerRuntime(docker, kubernetes, "auto"));
    }

    @Test
    void autoFallsBackToDocker() {
        DockerCliRuntime docker = mock(DockerCliRuntime.class);
        KubernetesRuntime kubernetes = mock(KubernetesRuntime.class);
        when(kubernetes.available()).thenReturn(false);
        assertSame(docker, new AgentRuntimeConfig().containerRuntime(docker, kubernetes, "auto"));
    }
}
