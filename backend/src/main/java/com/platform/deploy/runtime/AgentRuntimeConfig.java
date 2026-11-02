package com.platform.deploy.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Agent 运行时选择：
 * - agent.deploy.runtime=kubernetes：显式使用 K8s API
 * - agent.deploy.runtime=docker：显式使用本机 Docker
 * - auto（默认）：检测到集群 ServiceAccount 时用 K8s，否则回退 Docker
 */
@Configuration
public class AgentRuntimeConfig {

    @Bean
    @Primary
    public ContainerRuntime containerRuntime(DockerCliRuntime docker,
                                             KubernetesRuntime kubernetes,
                                             @Value("${agent.deploy.runtime:auto}") String mode) {
        if ("docker".equalsIgnoreCase(mode)) return docker;
        if ("kubernetes".equalsIgnoreCase(mode)) return kubernetes;
        return kubernetes.available() ? kubernetes : docker;
    }
}
