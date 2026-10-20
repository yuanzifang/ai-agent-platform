package com.platform.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式生成快照（Redis）：生成与 HTTP 连接解耦，断线后用 offset 续传。
 * key: chat:live:{conversationId}，TTL 10 分钟；只在内存中的增量写入做节流（200ms）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLiveStateService {

    private static final String PREFIX = "chat:live:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public record LiveState(String traceId, String content, String status, Long messageId,
                            long inputTokens, long outputTokens,
                            List<Map<String, Object>> citations, long updatedAt) {
    }

    public void start(Long conversationId, String traceId) {
        save(conversationId, new LiveState(traceId, "", "RUNNING", null, 0, 0, null,
                System.currentTimeMillis()));
    }

    public void running(Long conversationId, String traceId, String content,
                        long inputTokens, long outputTokens) {
        save(conversationId, new LiveState(traceId, content == null ? "" : content, "RUNNING", null,
                inputTokens, outputTokens, null, System.currentTimeMillis()));
    }

    public void finish(Long conversationId, String traceId, String content, String status,
                       Long messageId, long inputTokens, long outputTokens,
                       List<Map<String, Object>> citations) {
        save(conversationId, new LiveState(traceId, content == null ? "" : content, status, messageId,
                inputTokens, outputTokens, citations, System.currentTimeMillis()));
    }

    public LiveState get(Long conversationId) {
        try {
            String raw = redis.opsForValue().get(PREFIX + conversationId);
            if (raw == null || raw.isBlank()) return null;
            return objectMapper.readValue(raw, LiveState.class);
        } catch (Exception e) {
            log.debug("流式快照读取失败 conversation={}：{}", conversationId, e.getMessage());
            return null;
        }
    }

    public void clear(Long conversationId) {
        try {
            redis.delete(PREFIX + conversationId);
        } catch (Exception e) {
            log.debug("流式快照清理失败：{}", e.getMessage());
        }
    }

    private void save(Long conversationId, LiveState state) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("traceId", state.traceId());
            map.put("content", state.content());
            map.put("status", state.status());
            map.put("messageId", state.messageId());
            map.put("inputTokens", state.inputTokens());
            map.put("outputTokens", state.outputTokens());
            map.put("citations", state.citations());
            map.put("updatedAt", state.updatedAt());
            redis.opsForValue().set(PREFIX + conversationId,
                    objectMapper.writeValueAsString(map), TTL);
        } catch (Exception e) {
            log.warn("流式快照写入失败 conversation={}：{}", conversationId, e.getMessage());
        }
    }
}
