package com.platform.capability.service;

import com.platform.capability.mapper.ToolApprovalMapper;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolApprovalServiceTest {

    private final ToolApprovalMapper mapper = mock(ToolApprovalMapper.class);
    private final SysTenantMapper tenantMapper = mock(SysTenantMapper.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void autoExpireRunsOncePerTenantWithTenantContext() {
        SysTenant one = new SysTenant();
        one.setId(1L);
        SysTenant two = new SysTenant();
        two.setId(2L);
        when(tenantMapper.selectList(any())).thenReturn(List.of(one, two));
        List<Long> seen = new ArrayList<>();
        ToolApprovalService service = new ToolApprovalService(mapper, tenantMapper) {
            @Override
            public int expirePending() {
                seen.add(TenantContext.getTenantId());
                return 1;
            }
        };

        service.autoExpire();

        assertEquals(List.of(1L, 2L), seen);
        assertEquals(null, TenantContext.getTenantId());
    }
}
