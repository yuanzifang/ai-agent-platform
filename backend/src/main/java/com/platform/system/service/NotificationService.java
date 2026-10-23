package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.context.TenantContext;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.UserNotification;
import com.platform.system.mapper.UserNotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 站内通知：工作流审批等事件写入 user_notification，顶部铃铛读取。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserNotificationMapper mapper;

    public void notify(Long tenantId, List<Long> userIds, String type, String title,
                       String content, String link) {
        if (tenantId == null || userIds == null || userIds.isEmpty()) return;
        for (Long userId : userIds.stream().filter(Objects::nonNull).distinct().toList()) {
            try {
                UserNotification n = new UserNotification();
                n.setTenantId(tenantId);
                n.setUserId(userId);
                n.setType(truncate(type, 32));
                n.setTitle(truncate(title, 200));
                n.setContent(truncate(content, 1000));
                n.setLink(truncate(link, 200));
                n.setIsRead(0);
                n.setCreatedAt(LocalDateTime.now());
                mapper.insert(n);
            } catch (Exception e) {
                log.debug("通知写入失败 user={}：{}", userId, e.getMessage());
            }
        }
    }

    public Map<String, Object> list(JwtPrincipal principal, boolean unreadOnly, long page, long pageSize) {
        if (principal.platformAdmin()) {
            // 平台管理员不属于任何租户（通知均由租户业务产生），短路避免多租户插件抛"缺少租户上下文"
            return Map.of("list", java.util.List.of(), "total", 0, "unread", 0,
                    "page", page, "pageSize", pageSize);
        }
        Long userId = principal.userId();
        Page<UserNotification> p = mapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 50)),
                new LambdaQueryWrapper<UserNotification>()
                        .eq(UserNotification::getUserId, userId)
                        .eq(unreadOnly, UserNotification::getIsRead, 0)
                        .orderByDesc(UserNotification::getId));
        return Map.of("list", p.getRecords(), "total", p.getTotal(),
                "unread", unreadCount(principal), "page", page, "pageSize", pageSize);
    }

    public long unreadCount(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) return 0;
        if (principal.platformAdmin()) return 0;
        return mapper.selectCount(new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getUserId, principal.userId())
                .eq(UserNotification::getIsRead, 0));
    }

    public void markRead(Long id, JwtPrincipal principal) {
        if (principal.platformAdmin()) return;
        mapper.update(null, new LambdaUpdateWrapper<UserNotification>()
                .eq(UserNotification::getId, id)
                .eq(UserNotification::getUserId, principal.userId())
                .set(UserNotification::getIsRead, 1));
    }

    public void markAllRead(JwtPrincipal principal) {
        if (principal.platformAdmin()) return;
        mapper.update(null, new LambdaUpdateWrapper<UserNotification>()
                .eq(UserNotification::getUserId, principal.userId())
                .eq(UserNotification::getIsRead, 0)
                .set(UserNotification::getIsRead, 1));
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
