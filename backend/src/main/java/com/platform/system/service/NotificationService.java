package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.context.TenantContext;
import com.platform.common.i18n.BackendMessages;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.UserNotification;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.UserNotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 站内通知：工作流审批等事件写入 user_notification，顶部铃铛读取。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserNotificationMapper mapper;
    private final SysUserMapper userMapper;
    private final BackendMessages messages;

    public void notify(Long tenantId, List<Long> userIds, String type, String title,
                       String content, String link) {
        notify(tenantId, userIds, type, title, content, link, title, content);
    }

    /** 站内通知双语模板：按收件人 sys_user.locale 选择标题/正文。 */
    public void notify(Long tenantId, List<Long> userIds, String type, String titleZh,
                       String contentZh, String link, String titleEn, String contentEn) {
        if (tenantId == null || userIds == null || userIds.isEmpty()) return;
        for (Long userId : userIds.stream().filter(Objects::nonNull).distinct().toList()) {
            try {
                SysUser user = userMapper.selectById(userId);
                boolean en = user != null && user.getLocale() != null
                        && user.getLocale().toLowerCase().startsWith("en");
                String title = en && titleEn != null && !titleEn.isBlank() ? titleEn : titleZh;
                String content = en && contentEn != null && !contentEn.isBlank() ? contentEn : contentZh;
                save(tenantId, userId, type, title, content, link);
            } catch (Exception e) {
                log.debug("通知写入失败 user={}：{}", userId, e.getMessage());
            }
        }
    }

    /**
     * 资源包模板版站内通知：根据收件人 locale 解析 titleKey/contentKey。
     * 参数按 MessageFormat 顺序传入，例如 {0} 为审批标题、{1} 为运行输入。
     */
    public void notifyTemplate(Long tenantId, List<Long> userIds, String type, String link,
                               String titleKey, String contentKey, Object... args) {
        if (tenantId == null || userIds == null || userIds.isEmpty()) return;
        for (Long userId : userIds.stream().filter(Objects::nonNull).distinct().toList()) {
            try {
                SysUser user = userMapper.selectById(userId);
                Locale locale = user == null || user.getLocale() == null
                        ? Locale.SIMPLIFIED_CHINESE
                        : Locale.forLanguageTag(user.getLocale().replace('_', '-'));
                String title = messages.get(titleKey, locale, args);
                String content = messages.get(contentKey, locale, args);
                save(tenantId, userId, type, title, content, link);
            } catch (Exception e) {
                log.debug("通知模板写入失败 user={}：{}", userId, e.getMessage());
            }
        }
    }

    private void save(Long tenantId, Long userId, String type, String title, String content, String link) {
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
