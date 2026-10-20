package com.platform.conversation.service;

import reactor.core.Disposable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式会话注册表：本地保存当前实例的 Disposable，Redis 记录归属实例并广播停止信号，
 * 解决多副本下「停止生成」请求落到其他实例后无法取消的问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamRegistryService {

    public static final String STOP_CHANNEL = "chat:stop";
    private static final String KEY_PREFIX = "chat:active:";

    private final StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final Map<Long, Disposable> local = new ConcurrentHashMap<>();
    private RedisMessageListenerContainer container;

    @PostConstruct
    void init() {
        try {
            RedisConnectionFactory factory = redis.getConnectionFactory();
            if (factory == null) return;
            container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            container.addMessageListener((message, pattern) -> {
                try {
                    Long conversationId = Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8));
                    Disposable d = local.get(conversationId);
                    if (d != null && !d.isDisposed()) {
                        log.info("收到跨实例停止信号：conversation={}", conversationId);
                        d.dispose();
                    }
                } catch (Exception e) {
                    log.debug("停止信号处理失败：{}", e.getMessage());
                }
            }, new ChannelTopic(STOP_CHANNEL));
            container.afterPropertiesSet();
            container.start();
        } catch (Exception e) {
            log.warn("流式会话注册表初始化失败（多副本停止可能失效）：{}", e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.debug("Redis 监听容器停止失败：{}", e.getMessage());
            }
        }
    }

    public void register(Long conversationId, Disposable disposable) {
        local.put(conversationId, disposable);
        try {
            redis.opsForValue().set(KEY_PREFIX + conversationId, instanceId, Duration.ofMinutes(30));
        } catch (Exception e) {
            log.debug("流式会话注册 Redis 失败：{}", e.getMessage());
        }
    }

    /** 取消注册；返回此前本实例是否持有该流。 */
    public boolean unregister(Long conversationId) {
        boolean existed = local.remove(conversationId) != null;
        try {
            redis.delete(KEY_PREFIX + conversationId);
        } catch (Exception e) {
            log.debug("流式会话移除 Redis 失败：{}", e.getMessage());
        }
        return existed;
    }

    /** 停止：本实例直接 dispose；否则广播停止信号由持有实例处理。 */
    public boolean stop(Long conversationId) {
        Disposable d = local.get(conversationId);
        if (d != null && !d.isDisposed()) {
            d.dispose();
            return true;
        }
        try {
            redis.convertAndSend(STOP_CHANNEL, String.valueOf(conversationId));
        } catch (Exception e) {
            log.debug("停止信号广播失败：{}", e.getMessage());
        }
        return false;
    }

    public boolean isActive(Long conversationId) {
        if (local.containsKey(conversationId)) return true;
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + conversationId));
        } catch (Exception e) {
            return false;
        }
    }

    public String instanceId() {
        return instanceId;
    }
}
