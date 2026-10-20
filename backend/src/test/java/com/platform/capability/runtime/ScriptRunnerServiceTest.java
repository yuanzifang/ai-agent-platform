package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能脚本：网络隔离与取消（SIGKILL）验证。 */
class ScriptRunnerServiceTest {

    private ScriptRunnerService service;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(pythonAvailable(), "需要 python3");
        service = new ScriptRunnerService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "pythonCommand", "python3");
        ReflectionTestUtils.setField(service, "nodeCommand", "node");
        ReflectionTestUtils.setField(service, "maxOutputBytes", 65536);
        ReflectionTestUtils.setField(service, "maxCodeLength", 50000);
        ReflectionTestUtils.setField(service, "networkIsolation", true);
    }

    @Test
    void blocksSocketAccess() {
        String code = """
                import socket
                try:
                    socket.create_connection(("127.0.0.1", 6379), timeout=0.3)
                    print("NETWORK_ALLOWED")
                except Exception as ex:
                    print("BLOCKED:" + type(ex).__name__)
                """;
        ScriptRunnerService.Result result = service.run("PYTHON", code, Map.of(), 5000);
        assertFalse(result.output().contains("NETWORK_ALLOWED"), result.output());
        assertTrue(result.output().contains("BLOCKED"), result.output());
    }

    @Test
    void cancellationKillsRunningProcess() {
        String code = """
                import time
                time.sleep(10)
                print("DONE")
                """;
        AtomicLong start = new AtomicLong(System.currentTimeMillis());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.run("PYTHON", code, Map.of(), 30000,
                        () -> System.currentTimeMillis() - start.get() > 300));
        assertEquals(499, e.getCode());
        assertTrue(System.currentTimeMillis() - start.get() < 3000, "取消应在轮询周期内生效");
    }

    private boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
