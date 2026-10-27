package com.platform.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritePolicyGateTest {

    @Test
    void noConfigAndExplicitAllowAllAreEquivalent() {
        assertFalse(compare(Map.of(), Map.of()).morePermissive());
        assertFalse(compare(Map.of(), Map.of("writeToolPolicy", "ALLOW_ALL")).morePermissive());
    }

    @Test
    void modeExpansionIsPermissive() {
        var result = compare(Map.of("writeToolPolicy", "BLOCK"),
                Map.of("writeToolPolicy", "ALLOW_ALL"));
        assertTrue(result.morePermissive(), result.reasons().toString());
        assertTrue(result.reasons().get(0).contains("BLOCK"));
    }

    @Test
    void approvalIsMoreRestrictiveEvenWithWiderDetails() {
        var before = Map.<String, Object>of(
                "writeToolPolicy", "ALLOWLIST",
                "allowedWriteToolIds", List.of(1));
        var after = Map.<String, Object>of(
                "writeToolPolicy", "APPROVAL",
                "allowedWriteToolIds", List.of(1, 2, 3));
        assertFalse(compare(before, after).morePermissive());
    }

    @Test
    void allowlistExpansionIsPermissiveButRemovalIsNot() {
        var before = Map.<String, Object>of(
                "writeToolPolicy", "ALLOWLIST",
                "allowedWriteToolIds", List.of(1, 2));
        var expanded = Map.<String, Object>of(
                "writeToolPolicy", "ALLOWLIST",
                "allowedWriteToolIds", List.of(1, 2, 3));
        var narrowed = Map.<String, Object>of(
                "writeToolPolicy", "ALLOWLIST",
                "allowedWriteToolIds", List.of(1));

        assertTrue(compare(before, expanded).morePermissive());
        assertFalse(compare(before, narrowed).morePermissive());
    }

    @Test
    void largerOrRemovedArgumentLimitIsPermissive() {
        assertTrue(compare(caps(4096), caps(8192)).morePermissive());
        assertTrue(compare(caps(5000), caps(null)).morePermissive());
        assertFalse(compare(caps(100000), caps(null)).morePermissive());
        assertFalse(compare(caps(8192), caps(4096)).morePermissive());
    }

    @Test
    void argumentWhitelistExpansionOrRemovalIsPermissive() {
        var restricted = Map.<String, Object>of(
                "writeToolPolicy", "ALLOW_ALL",
                "writeToolArgRules", Map.of("1", List.of("orderId", "amount")));
        var expanded = Map.<String, Object>of(
                "writeToolPolicy", "ALLOW_ALL",
                "writeToolArgRules", Map.of("1", List.of("orderId", "amount", "remark")));
        var narrowed = Map.<String, Object>of(
                "writeToolPolicy", "ALLOW_ALL",
                "writeToolArgRules", Map.of("1", List.of("orderId")));

        assertTrue(compare(restricted, expanded).morePermissive());
        assertTrue(compare(restricted, caps(null)).morePermissive());
        assertFalse(compare(restricted, narrowed).morePermissive());
        assertFalse(compare(caps(null), restricted).morePermissive());
    }

    @Test
    void patternRemovalOrChangeRequiresForceButAdditionDoesNot() {
        var restricted = Map.<String, Object>of(
                "writeToolPolicy", "ALLOW_ALL",
                "writeToolArgPatterns", Map.of("1", Map.of("orderId", "^[A-Z0-9-]+$")));
        var changed = Map.<String, Object>of(
                "writeToolPolicy", "ALLOW_ALL",
                "writeToolArgPatterns", Map.of("1", Map.of("orderId", ".*")));
        var extra = Map.<String, Object>of(
                "writeToolPolicy", "ALLOW_ALL",
                "writeToolArgPatterns", Map.of("1", Map.of(
                        "orderId", "^[A-Z0-9-]+$", "remark", "^.{0,20}$")));

        assertTrue(compare(restricted, changed).morePermissive());
        assertTrue(compare(restricted, caps(null)).morePermissive());
        assertFalse(compare(restricted, extra).morePermissive());
        assertFalse(compare(restricted, restricted).morePermissive());
    }

    @Test
    void amountLimitIncreaseOrRemovalIsPermissive() {
        var limited = limits(100);
        assertTrue(compare(limited, limits(200)).morePermissive());
        assertTrue(compare(limited, caps(null)).morePermissive());
        assertFalse(compare(limited, limits(50)).morePermissive());
        assertFalse(compare(limits(100), limited).morePermissive());
    }

    private WritePolicyGate.Result compare(Map<String, Object> before, Map<String, Object> after) {
        return WritePolicyGate.compare(before, after);
    }

    private Map<String, Object> caps(Integer maxArgs) {
        if (maxArgs == null) return Map.of("writeToolPolicy", "ALLOW_ALL");
        return Map.of("writeToolPolicy", "ALLOW_ALL", "writeToolMaxArgs", maxArgs);
    }

    private Map<String, Object> limits(double amount) {
        return Map.of("writeToolPolicy", "ALLOW_ALL",
                "writeToolAmountLimits", Map.of("1", Map.of("amount", amount)));
    }
}
