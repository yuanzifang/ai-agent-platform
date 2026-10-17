package com.platform.conversation.controller;

import com.platform.common.response.ApiResponse;
import com.platform.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话分享只读回看（需求文档 8.1）：签名 token 免登录，过期失效。
 */
@RestController
@RequestMapping("/api/v1/share")
@RequiredArgsConstructor
public class ShareController {

    private final ConversationService conversationService;

    @GetMapping("/{token}")
    public ApiResponse<Map<String, Object>> view(@PathVariable String token) {
        return ApiResponse.ok(conversationService.shareView(token));
    }
}
