package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器沙箱实跑验证（可选）：-Dskill.docker.it=true 时执行。
 * 验证真实 docker run：脚本正常执行输出、--network none 下网络访问被拒。
 * 前置：本机 Docker 可用，且已拉取 python:3.12-alpine（国内可走镜像源）。
 */
@EnabledIfSystemProperty(named = "skill.docker.it", matches = "true")
class ScriptRunnerDockerIT {

    private ScriptRunnerService service;

    @BeforeEach
    void setUp() {
        service = new ScriptRunnerService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "pythonCommand", "python3");
        ReflectionTestUtils.setField(service, "nodeCommand", "node");
        ReflectionTestUtils.setField(service, "maxOutputBytes", 65536);
        ReflectionTestUtils.setField(service, "maxCodeLength", 50000);
        ReflectionTestUtils.setField(service, "networkIsolation", true);
        ReflectionTestUtils.setField(service, "runtime", "docker");
        ReflectionTestUtils.setField(service, "dockerBin", "docker");
        ReflectionTestUtils.setField(service, "dockerPythonImage", "python:3.12-alpine");
        ReflectionTestUtils.setField(service, "dockerNodeImage", "node:22-alpine");
        ReflectionTestUtils.setField(service, "dockerMemory", "256m");
        ReflectionTestUtils.setField(service, "dockerCpus", "0.5");
        ReflectionTestUtils.setField(service, "dockerPidsLimit", 64);
    }

    @Test
    void scriptRunsInContainerAndReceivesArgs() {
        ScriptRunnerService.Result r = service.run("PYTHON",
                "import json;print('ARGS_OK', params['k'])", Map.of("k", "v1"), 30000);
        assertTrue(r.output().contains("ARGS_OK v1"), "实际输出：" + r.output());
    }

    @Test
    void runsInContainerNotProcessFallback() {
        // 判别性证据：Docker 容器内存在 /.dockerenv；若静默回退进程模式则宿主机上为 False
        ScriptRunnerService.Result r = service.run("PYTHON",
                "import os;print('DOCKERENV=' + str(os.path.exists('/.dockerenv')))", Map.of(), 30000);
        assertTrue(r.output().contains("DOCKERENV=True"), "应运行在容器内，实际输出：" + r.output());
    }

    @Test
    void networkIsDeniedInsideContainer() {
        ScriptRunnerService.Result r = service.run("PYTHON", """
                import socket
                try:
                    socket.create_connection(("example.com", 443), timeout=3)
                    print("NETWORK_ALLOWED")
                except Exception as e:
                    print("BLOCKED", type(e).__name__)
                """, Map.of(), 30000);
        assertTrue(r.output().contains("BLOCKED"), "容器内网络应被拒绝，实际输出：" + r.output());
        assertTrue(!r.output().contains("NETWORK_ALLOWED"));
    }
}
