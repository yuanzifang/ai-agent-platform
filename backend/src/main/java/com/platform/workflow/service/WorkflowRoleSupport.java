package com.platform.workflow.service;

import java.util.Locale;

/** 工作流审批角色强度比较：并行门禁需要取分支内最高角色要求。 */
final class WorkflowRoleSupport {

    private WorkflowRoleSupport() {
    }

    static String strongerRole(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) return current;
        return roleRank(candidate) > roleRank(current) ? candidate : current;
    }

    private static int roleRank(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "OWNER" -> 5;
            case "ADMIN" -> 4;
            case "EDITOR" -> 3;
            case "MEMBER" -> 2;
            case "RESTRICTED" -> 1;
            default -> 0;
        };
    }
}
