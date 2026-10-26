package com.platform.deploy.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Docker 冒烟：CI 的 gateway job 构建 agent-gateway:ci 后执行。
 * 仅设置 DOCKER_IT=1 时运行；本地默认跳过，避免要求开发机安装 Docker。
 */
@EnabledIfEnvironmentVariable(named = "DOCKER_IT", matches = "1")
class DockerCliRuntimeIT {

    @Test
    @Timeout(90)
    void createsStartsInspectsAndRemovesGatewayContainer() throws Exception {
        String image = System.getenv().getOrDefault("DOCKER_IT_IMAGE", "agent-gateway:ci");
        DockerCliRuntime runtime = new DockerCliRuntime();
        ReflectionTestUtils.setField(runtime, "dockerBin", "docker");
        assertTrue(runtime.available(), "Docker 不可用，DOCKER_IT=1 时 CI 必须提供 Docker");

        String name = "aap-gateway-it-" + System.currentTimeMillis();
        int port = freePort();
        try {
            try {
                runtime.remove(name);
            } catch (Exception ignored) {
                // 首次运行时容器不存在
            }
            String id = runtime.create(new ContainerRuntime.CreateRequest(
                    name, image, "127.0.0.1", port, 8080,
                    Map.of(), Map.of("aap.test", "gateway"), List.of(), List.of()));
            assertNotNull(id);
            assertFalse(id.isBlank());

            runtime.start(name);
            awaitHealthy(port, runtime, name);

            ContainerRuntime.Info running = runtime.inspect(name);
            assertTrue(running.running());
            assertEquals("running", running.status());
            assertTrue(runtime.logs(name, 100).contains("agent-gateway started"));

            runtime.stop(name);
            ContainerRuntime.Info stopped = runtime.inspect(name);
            assertFalse(stopped.running());
        } finally {
            try {
                runtime.remove(name);
            } catch (Exception ignored) {
                // 清理失败不掩盖主断言
            }
        }
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitHealthy(int port, DockerCliRuntime runtime, String name) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        Exception last = null;
        for (int i = 0; i < 60; i++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && "ok".equals(response.body())) return;
                last = new IllegalStateException("health status " + response.statusCode());
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("容器未在超时时间内健康，logs=" + runtime.logs(name, 50), last);
    }
}
