package com.platform.workflow.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 并行门禁角色合并：空角色不 NPE、取分支内最强角色（回归 90d9cb1 修复的 E2E 空指针）。 */
class WorkflowRoleSupportTest {

    @Test
    void nullOrBlankCandidateKeepsCurrent() {
        assertNull(WorkflowRoleSupport.strongerRole(null, null));
        assertNull(WorkflowRoleSupport.strongerRole(null, "  "));
        assertEquals("ADMIN", WorkflowRoleSupport.strongerRole("ADMIN", null));
        assertEquals("ADMIN", WorkflowRoleSupport.strongerRole("ADMIN", ""));
    }

    @Test
    void strongerCandidateWinsByRank() {
        assertEquals("OWNER", WorkflowRoleSupport.strongerRole("ADMIN", "OWNER"));
        assertEquals("ADMIN", WorkflowRoleSupport.strongerRole("MEMBER", "ADMIN"));
        assertEquals("EDITOR", WorkflowRoleSupport.strongerRole("EDITOR", "MEMBER"));
        assertEquals("RESTRICTED", WorkflowRoleSupport.strongerRole(null, "RESTRICTED"));
    }

    @Test
    void weakerOrUnknownCandidateDoesNotDowngrade() {
        assertEquals("ADMIN", WorkflowRoleSupport.strongerRole("ADMIN", "MEMBER"));
        // 未知角色按 0 档，不得把已有角色清掉
        assertEquals("ADMIN", WorkflowRoleSupport.strongerRole("ADMIN", "SOME_NEW_ROLE"));
        assertNull(WorkflowRoleSupport.strongerRole(null, "SOME_NEW_ROLE"));
    }

    @Test
    void caseInsensitiveRank() {
        // 比较按大小写不敏感，返回值保留候选角色原样
        assertEquals("owner", WorkflowRoleSupport.strongerRole("admin", "owner"));
    }
}
