package com.platform.system.service;

import com.platform.common.i18n.BackendMessages;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.UserNotification;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.UserNotificationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    @Test
    void usesRecipientLocaleForTitleAndContent() {
        UserNotificationMapper mapper = mock(UserNotificationMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(7L);
        user.setLocale("en-US");
        when(userMapper.selectById(7L)).thenReturn(user);
        NotificationService service = new NotificationService(mapper, userMapper, messages());

        service.notify(1L, List.of(7L), "workflow_result", "中文标题", "中文内容", "/x",
                "English title", "English content");

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        verify(mapper).insert(captor.capture());
        assertEquals("English title", captor.getValue().getTitle());
        assertEquals("English content", captor.getValue().getContent());
    }

    @Test
    void resolvesTemplateByRecipientLocale() {
        UserNotificationMapper mapper = mock(UserNotificationMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(7L);
        user.setLocale("en-US");
        when(userMapper.selectById(7L)).thenReturn(user);
        NotificationService service = new NotificationService(mapper, userMapper, messages());

        service.notifyTemplate(1L, List.of(7L), "workflow_approval", "/approvals",
                "notification.workflow.approval.title", "notification.workflow.approval.content",
                "退款审批", "订单 42");

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        verify(mapper).insert(captor.capture());
        assertEquals("Pending approval: 退款审批", captor.getValue().getTitle());
        assertEquals("Agent workflow 退款审批 requires approval; input: 订单 42", captor.getValue().getContent());
    }

    private BackendMessages messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new BackendMessages(source);
    }
}
