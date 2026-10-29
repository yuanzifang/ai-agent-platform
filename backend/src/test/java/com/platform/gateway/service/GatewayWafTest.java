package com.platform.gateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayWafTest {

    @Test
    void allowsNormalProxyRequest() {
        assertNull(GatewayWaf.check("GET", "/api/v1/gateway/call/abc/orders/42", "page=2&q=hello"));
        assertNull(GatewayWaf.check("POST", "/api/v1/gateway/call/abc/orders", null));
    }

    @Test
    void blocksMethodAndLengthAbuse() {
        assertEquals("method TRACE", GatewayWaf.check("TRACE", "/x", null));
        assertEquals("uri length", GatewayWaf.check("GET", "/" + "a".repeat(2049), null));
        assertEquals("query length", GatewayWaf.check("GET", "/x", "a=" + "b".repeat(8193)));
    }

    @Test
    void blocksEncodedAndDoubleEncodedTraversal() {
        assertEquals("path traversal", GatewayWaf.check("GET", "/api/files/%2e%2e/secret", null));
        assertEquals("path traversal", GatewayWaf.check("GET", "/api/files/%252e%252e/secret", null));
        assertEquals("path traversal", GatewayWaf.check("GET", "/api/files/%2e%2e%5csecret", null));
    }

    @Test
    void blocksCrLfAndNullBytes() {
        assertEquals("control character", GatewayWaf.check("GET", "/x%0d%0aSet-Cookie:x", null));
        assertEquals("control character", GatewayWaf.check("GET", "/x", "a=%00"));
    }

    @Test
    void blocksHighConfidenceSqlInjectionAndXss() {
        assertEquals("sqli", GatewayWaf.check("GET", "/x", "q=1%20union%20select%20password%20from%20users"));
        assertEquals("sqli", GatewayWaf.check("GET", "/x", "q='%20or%201=1--"));
        assertEquals("xss", GatewayWaf.check("GET", "/x", "q=%3Cscript%3Ealert(1)%3C/script%3E"));
        assertEquals("xss", GatewayWaf.check("GET", "/x", "q=javascript:alert(1)"));
    }
}
