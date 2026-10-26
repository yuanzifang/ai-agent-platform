package com.platform.deploy.runtime;

import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerCliRuntimeTest {

    @TempDir
    Path tempDir;

    private DockerCliRuntime runtimeWithScript(String body) throws Exception {
        Path script = tempDir.resolve("fake-docker");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        script.toFile().setExecutable(true);
        DockerCliRuntime runtime = new DockerCliRuntime();
        ReflectionTestUtils.setField(runtime, "dockerBin", script.toString());
        return runtime;
    }

    private String readArgs() throws Exception {
        Path log = tempDir.resolve("args.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    @Test
    void buildsCreateCommandAndParsesInspect() throws Exception {
        Path log = tempDir.resolve("args.log");
        DockerCliRuntime runtime = runtimeWithScript("""
                echo "$@" >> "%s"
                case "$1" in
                  info) echo "29.9.2" ;;
                  create) echo "container-id-1" ;;
                  inspect) echo '{"Status":"running","Running":true,"Error":""}' ;;
                  logs) echo "line-1"; echo "line-2" ;;
                esac
                """.formatted(log));
        assertTrue(runtime.available());
        assertEquals("29.9.2", runtime.version());

        String id = runtime.create(new ContainerRuntime.CreateRequest(
                "aap-test", "aap/agent-gateway:1", "127.0.0.1", 19099, 8080,
                Map.of("TOKEN", "secret", "MODE", "test"),
                Map.of("aap.agent", "1"),
                List.of("/tmp/data:/data:ro"),
                List.of("--network", "bridge")));

        assertEquals("container-id-1", id);
        String args = readArgs();
        assertTrue(args.contains("create --name aap-test --restart unless-stopped"));
        assertTrue(args.contains("--label aap.agent=1"));
        assertTrue(args.contains("-p 127.0.0.1:19099:8080"));
        assertTrue(args.contains("--add-host host.docker.internal:host-gateway"));
        assertTrue(args.contains("--health-cmd wget -q -O- http://127.0.0.1:8080/healthz || exit 1"));
        assertTrue(args.contains("-e TOKEN=secret"));
        assertTrue(args.contains("-e MODE=test"));
        assertTrue(args.contains("-v /tmp/data:/data:ro"));
        assertTrue(args.contains("--network bridge aap/agent-gateway:1"));

        runtime.start("aap-test");
        runtime.stop("aap-test");
        assertEquals("line-1\nline-2", runtime.logs("aap-test", 20));
        runtime.remove("aap-test");
        args = readArgs();
        assertTrue(args.contains("start aap-test"));
        assertTrue(args.contains("stop aap-test"));
        assertTrue(args.contains("logs --tail 20 aap-test"));
        assertTrue(args.contains("rm -f aap-test"));

        ContainerRuntime.Info info = runtime.inspect("aap-test");
        assertEquals("aap-test", info.id());
        assertTrue(info.running());
        assertEquals("running", info.status());
        assertEquals("", info.error());
    }

    @Test
    void dockerFailureBecomesBusinessError() throws Exception {
        DockerCliRuntime runtime = runtimeWithScript("""
                echo "daemon exploded"
                exit 3
                """);
        assertFalse(runtime.available());
        BusinessException error = assertThrows(BusinessException.class,
                () -> runtime.remove("missing"));
        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("daemon exploded"));
    }

    @Test
    void invalidInspectJsonBecomesBadGateway() throws Exception {
        DockerCliRuntime runtime = runtimeWithScript("""
                echo "not-json"
                """);
        BusinessException error = assertThrows(BusinessException.class,
                () -> runtime.inspect("x"));
        assertEquals(502, error.getCode());
        assertTrue(error.getMessage().contains("解析容器状态失败"));
    }
}
