package com.platform.common.security;

import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkPolicyServiceTest {

    @Test
    void rejectsNonHttpScheme() {
        NetworkPolicyService service = new NetworkPolicyService();
        ReflectionTestUtils.setField(service, "allowPrivate", true);
        ReflectionTestUtils.setField(service, "allowedHosts", "");
        assertThrows(BusinessException.class, () -> service.validateHttpUrl("ftp://example.com/x", "URL"));
        assertThrows(BusinessException.class, () -> service.validateHttpUrl("", "URL"));
    }

    @Test
    void allowsPrivateWhenConfigured() {
        NetworkPolicyService service = new NetworkPolicyService();
        ReflectionTestUtils.setField(service, "allowPrivate", true);
        ReflectionTestUtils.setField(service, "allowedHosts", "");
        assertDoesNotThrow(() -> service.validateHttpUrl("http://127.0.0.1:18080/mcp", "URL"));
    }

    @Test
    void publicValidatorBlocksPrivateEvenWhenGlobalAllows() {
        NetworkPolicyService service = new NetworkPolicyService();
        ReflectionTestUtils.setField(service, "allowPrivate", true);
        ReflectionTestUtils.setField(service, "allowedHosts", "");
        assertThrows(BusinessException.class,
                () -> service.validatePublicHttpUrl("http://127.0.0.1:8080/actuator/health", "URL"));
        assertThrows(BusinessException.class,
                () -> service.validatePublicHttpUrl("http://169.254.169.254/latest/meta-data/", "URL"));
    }

    @Test
    void resolvePublicHostBlocksPrivateAddress() {
        NetworkPolicyService service = new NetworkPolicyService();
        ReflectionTestUtils.setField(service, "allowedHosts", "");
        assertThrows(BusinessException.class, () -> service.resolvePublicHost("127.0.0.1"));
        assertThrows(BusinessException.class, () -> service.resolvePublicHost("localhost"));
    }

    @Test
    void blocksPrivateWhenDisabled() {
        NetworkPolicyService service = new NetworkPolicyService();
        ReflectionTestUtils.setField(service, "allowPrivate", false);
        ReflectionTestUtils.setField(service, "allowedHosts", "");
        assertThrows(BusinessException.class, () -> service.validateHttpUrl("http://127.0.0.1/x", "URL"));
        assertThrows(BusinessException.class, () -> service.validateHttpUrl("http://localhost/x", "URL"));
    }
}
