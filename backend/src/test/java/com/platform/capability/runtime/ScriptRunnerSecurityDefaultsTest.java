package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 安全默认值：脚本默认关闭，docker 不可用时必须失败关闭。 */
class ScriptRunnerSecurityDefaultsTest {

    @Test
    void scriptExecutionIsDisabledByDefault() {
        ScriptRunnerService service = new ScriptRunnerService(new ObjectMapper());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.run("PYTHON", "print('should-not-run')", Map.of(), 1000));
        assertEquals(403, e.getCode());
    }

    @Test
    void dockerRuntimeFailsClosedWhenDockerIsUnavailable() {
        ScriptRunnerService service = new ScriptRunnerService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "runtime", "docker");
        ReflectionTestUtils.setField(service, "maxCodeLength", 50000);
        ReflectionTestUtils.setField(service, "dockerBin", "definitely-not-installed-aap-docker");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.run("PYTHON", "print('should-not-run')", Map.of(), 1000));
        assertEquals(503, e.getCode());
    }
}
