package com.platform.security;

import java.util.List;

/**
 * 认证主体：来自 JWT claims（userId / tenantId / platformAdmin / roles）。
 */
public record JwtPrincipal(Long userId, Long tenantId, boolean platformAdmin, List<String> roles) {
}
