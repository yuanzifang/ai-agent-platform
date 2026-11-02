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
}
