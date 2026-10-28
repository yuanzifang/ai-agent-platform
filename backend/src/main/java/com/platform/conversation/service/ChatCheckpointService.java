package com.platform.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.conversation.entity.ChatCheckpoint;
import com.platform.conversation.mapper.ChatCheckpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 对话级持久化 checkpoint：Redis live state 丢失/过期后仍能回放最近内容。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCheckpointService {

    private final ChatCheckpointMapper mapper;
    private final ObjectMapper objectMapper;

    public void save(Long conversationId, String traceId, String status, String content,
                     Long messageId, long inputTokens, long outputTokens,
                     List<Map<String, Object>> citations) {
        save(TenantContext.getTenantId(), conversationId, traceId, status, content,
                messageId, inputTokens, outputTokens, citations);
    }

    /** 显式传入租户，供 Reactor/异步回调使用（这些线程可能没有 TenantContext）。 */
    public void save(Long tenantId, Long conversationId, String traceId, String status, String content,
                     Long messageId, long inputTokens, long outputTokens,
                     List<Map<String, Object>> citations) {
        if (conversationId == null || tenantId == null) return;
        try {
            ChatCheckpoint row = mapper.selectOne(new LambdaQueryWrapper<ChatCheckpoint>()
                    .eq(ChatCheckpoint::getConversationId, conversationId).last("LIMIT 1"));
            if (row == null) {
                row = new ChatCheckpoint();
                row.setConversationId(conversationId);
                row.setTenantId(tenantId);
                row.setCreatedAt(LocalDateTime.now());
            }
            row.setTraceId(traceId);
            row.setStatus(status);
            row.setContent(content == null ? "" : content);
            row.setMessageId(messageId);
            row.setInputTokens(inputTokens);
            row.setOutputTokens(outputTokens);
            row.setCitationsJson(citations == null ? null : objectMapper.writeValueAsString(citations));
            row.setUpdatedAt(LocalDateTime.now());
            if (row.getId() == null) mapper.insert(row);
            else mapper.updateById(row);
        } catch (Exception e) {
            log.debug("对话 checkpoint 写入失败 conversation={}：{}", conversationId, e.getMessage());
        }
    }

    public ChatCheckpoint get(Long conversationId) {
        if (conversationId == null) return null;
        try {
            return mapper.selectOne(new LambdaQueryWrapper<ChatCheckpoint>()
                    .eq(ChatCheckpoint::getConversationId, conversationId).last("LIMIT 1"));
        } catch (Exception e) {
            log.debug("对话 checkpoint 读取失败 conversation={}：{}", conversationId, e.getMessage());
            return null;
        }
    }
}
