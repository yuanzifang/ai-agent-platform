package com.platform.channel.controller;

import com.platform.channel.service.ChannelService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 渠道接口：公开入站 + 管理端 CRUD/测试/消息记录。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    /** 公开入站：token 鉴权；飞书/企微按类型验签解密。 */
    @PostMapping("/channels/{token}/message")
    public ApiResponse<Map<String, Object>> inbound(@PathVariable String token,
                                                    @RequestBody(required = false) String rawBody,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(channelService.handleInbound(token, rawBody,
                headers(request), params(request)));
    }

    /** 企业微信 URL 验证（GET echostr）。 */
    @GetMapping(value = "/channels/{token}/wecom/verify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> wecomVerify(@PathVariable String token, HttpServletRequest request) {
        return ResponseEntity.ok(channelService.wecomVerify(token, params(request)));
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> map = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            map.put(name, request.getHeader(name));
        }
        return map;
    }

    private Map<String, String> params(HttpServletRequest request) {
        Map<String, String> map = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> map.put(k, v == null || v.length == 0 ? null : v[0]));
        return map;
    }

    @GetMapping("/agent/{agentId}/channels")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long agentId) {
        return ApiResponse.ok(channelService.list(agentId));
    }

    @PostMapping("/agent/{agentId}/channels")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> create(@PathVariable Long agentId,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(channelService.create(agentId, req, principal));
    }

    @PutMapping("/agent/{agentId}/channels/{id}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long agentId, @PathVariable Long id,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(channelService.update(agentId, id, req, principal));
    }

    @DeleteMapping("/agent/{agentId}/channels/{id}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> delete(@PathVariable Long agentId, @PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        channelService.delete(agentId, id, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/agent/{agentId}/channels/{id}/test")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long agentId, @PathVariable Long id,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(channelService.test(agentId, id, principal));
    }

    @GetMapping("/agent/{agentId}/channels/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable Long agentId, @PathVariable Long id,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(channelService.messages(agentId, id, page, pageSize));
    }
}
