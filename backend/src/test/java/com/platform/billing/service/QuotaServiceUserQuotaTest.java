package com.platform.billing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.mapper.BillingAccountMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.mapper.ChatAttachmentMapper;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.UserQuota;
import com.platform.system.mapper.SysTenantQuotaMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.UserQuotaMapper;
import com.platform.system.service.AlertWebhookService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotaServiceUserQuotaTest {

    private BillingUsageMapper usageMapper;
    private UserQuotaMapper userQuotaMapper;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserQuota.class);
        usageMapper = mock(BillingUsageMapper.class);
        userQuotaMapper = mock(UserQuotaMapper.class);
        service = new QuotaService(
                mock(SysTenantQuotaMapper.class), mock(AgentRegistryMapper.class),
                mock(KnowledgeBaseMapper.class), usageMapper, mock(ChatAttachmentMapper.class),
                mock(KnowledgeDocumentMapper.class), mock(BillingAccountMapper.class),
                mock(AlertWebhookService.class), mock(SysUserMapper.class), userQuotaMapper,
                mock(com.platform.common.mail.MailService.class),
                mock(StringRedisTemplate.class), new SimpleMeterRegistry());
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dailyTokenLimitRejectsOverrun() {
        UserQuota q = new UserQuota();
        q.setEnabled(1);
        q.setDailyTokenLimit(100L);
        when(userQuotaMapper.selectOne(any())).thenReturn(q);
        when(usageMapper.sumTokensTodayByUser(7L)).thenReturn(80L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.checkUserQuota(7L, 30));
        assertEquals(429, error.getCode());
    }

    @Test
    void disabledOrAbsentLimitDoesNotBlock() {
        UserQuota q = new UserQuota();
        q.setEnabled(0);
        q.setDailyTokenLimit(1L);
        when(userQuotaMapper.selectOne(any())).thenReturn(q);
        assertDoesNotThrow(() -> service.checkUserQuota(7L, 1000));

        when(userQuotaMapper.selectOne(any())).thenReturn(null);
        assertDoesNotThrow(() -> service.checkUserQuota(7L, 1000));
    }

    @Test
    void monthlyCostLimitRejectsOverrun() {
        UserQuota q = new UserQuota();
        q.setEnabled(1);
        q.setMonthlyCostLimit(new BigDecimal("10.00"));
        when(userQuotaMapper.selectOne(any())).thenReturn(q);
        when(usageMapper.sumMonthCostByUser(any(), any())).thenReturn(new BigDecimal("12.00"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.checkUserQuota(7L, 10));
        assertEquals(429, error.getCode());
    }
}
