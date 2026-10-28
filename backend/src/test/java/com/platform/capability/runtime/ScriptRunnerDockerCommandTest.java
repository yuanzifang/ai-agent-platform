package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 容器沙箱命令构造与硬约束断言（不起容器，纯命令级验证）。 */
class ScriptRunnerDockerCommandTest {

    private ScriptRunnerService serviceWithDefaults() {
        ScriptRunnerService service = new ScriptRunnerService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "dockerBin", "docker");
        ReflectionTestUtils.setField(service, "dockerPythonImage", "python:3.12-alpine");
        ReflectionTestUtils.setField(service, "dockerNodeImage", "node:22-alpine");
        ReflectionTestUtils.setField(service, "dockerMemory", "256m");
        ReflectionTestUtils.setField(service, "dockerCpus", "0.5");
        ReflectionTestUtils.setField(service, "dockerPidsLimit", 64);
        return service;
    }

    @Test
    void pythonCommandCarriesHardConstraints() {
        List<String> c = serviceWithDefaults().dockerCommand(
                "/usr/local/bin/docker", "PYTHON",
                "import os, json\nparams = json.loads(os.environ.get(\"SKILL_ARGS\") or \"{}\")\nprint('x')",
                "{\"a\":1}", "skill-abc123");
        assertEquals("/usr/local/bin/docker", c.get(0));
        assertEquals(List.of("run", "--rm", "--name", "skill-abc123"), c.subList(1, 5));
        assertTrue(c.contains("--network"));
        assertEquals("none", c.get(c.indexOf("--network") + 1), "必须无网络");
        assertTrue(c.contains("--memory") && c.contains("256m"));
        assertTrue(c.contains("--cpus") && c.contains("0.5"));
        assertTrue(c.contains("--pids-limit") && c.contains("64"));
        assertTrue(c.contains("--cap-drop") && c.contains("ALL"), "必须丢弃全部 capability");
        assertTrue(c.contains("--security-opt") && c.contains("no-new-privileges"));
        assertFalse(c.contains("-v"), "不得挂载宿主路径（macOS 临时目录不在共享列表且防路径泄漏）");
        assertTrue(c.contains("SKILL_ARGS={\"a\":1}"), "参数经 -e 注入");
        assertEquals("python:3.12-alpine", c.get(c.size() - 5));
        assertEquals("python3", c.get(c.size() - 4));
        assertEquals("-u", c.get(c.size() - 3));
        assertEquals("-c", c.get(c.size() - 2));
        assertTrue(c.get(c.size() - 1).contains("print"), "脚本经末位参数直传");
    }

    @Test
    void jsCommandUsesNodeImageAndEntrypoint() {
        List<String> c = serviceWithDefaults().dockerCommand(
                "docker", "JAVASCRIPT", "const params = {};\nprint('x');", "{}", "skill-x");
        assertEquals("node:22-alpine", c.get(c.size() - 4));
        assertEquals("node", c.get(c.size() - 3));
        assertEquals("-e", c.get(c.size() - 2));
        assertTrue(c.get(c.size() - 1).contains("params"));
    }

    @Test
    void imageOverrideTakesEffect() {
        ScriptRunnerService service = serviceWithDefaults();
        ReflectionTestUtils.setField(service, "dockerPythonImage", "registry.cn-hangzhou.aliyuncs.com/mirror/python:3.12-alpine");
        List<String> c = service.dockerCommand("docker", "PYTHON", "print('x')", "{}", "skill-x");
        assertEquals("registry.cn-hangzhou.aliyuncs.com/mirror/python:3.12-alpine", c.get(c.size() - 5));
        assertFalse(c.contains("--network=true"));
    }

    @Test
    void idempotencyKeyIsInjectedAsEnv() {
        List<String> c = serviceWithDefaults().dockerCommand(
                "docker", "PYTHON", "print('x')", "{}", "skill-x", "run-1:skill-x:1");
        assertTrue(c.contains("SKILL_IDEMPOTENCY_KEY=run-1:skill-x:1"));
    }
}
