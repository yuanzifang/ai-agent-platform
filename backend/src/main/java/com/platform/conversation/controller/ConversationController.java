package com.platform.conversation.controller;

import com.platform.common.response.ApiResponse;
import com.platform.conversation.service.ChatStreamService;
import com.platform.conversation.service.ConversationService;
import com.platform.security.JwtPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 会话对话接口（需求文档 7.2 / 7.3 / 7.4 SSE 协议）。
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatStreamService chatStreamService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(conversationService.list(keyword, page, pageSize, principal));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(conversationService.create(req.agentId(), req.title(), principal));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(conversationService.detail(id, principal));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        conversationService.delete(id, principal);
        return ApiResponse.ok();
    }

    /** 发送消息：stream=true 返回 SseEmitter（自动 text/event-stream），false 返回 JSON（7.3）。 */
    @PostMapping("/{id}/messages")
    public Object send(@PathVariable Long id,
                       @RequestBody SendRequest req,
                       @AuthenticationPrincipal JwtPrincipal principal) {
        Object result = chatStreamService.send(id, new ChatStreamService.SendRequest(
                req.message(), req.stream(), req.variables(), req.attachmentIds()), principal);
        return result instanceof SseEmitter ? result : ApiResponse.ok(result);
    }

    @PostMapping("/{id}/attachments")
    public ApiResponse<Map<String, Object>> uploadAttachment(@PathVariable Long id,
                                                             @RequestPart("file") MultipartFile file,
                                                             @AuthenticationPrincipal JwtPrincipal principal) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.error(400, "附件不能为空");
        }
        try {
            ConversationService.MultipartFilePart part = new ConversationService.MultipartFilePart(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes());
            return ApiResponse.ok(conversationService.saveAttachment(id, part, principal));
        } catch (com.platform.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            return ApiResponse.error(400, "附件读取失败：" + e.getMessage());
        }
    }

    @PostMapping("/{id}/messages/{mid}/stop")
    public ApiResponse<Void> stop(@PathVariable Long id, @PathVariable Long mid,
                                  @AuthenticationPrincipal JwtPrincipal principal) {
        chatStreamService.stop(id, mid, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/messages/{mid}/regenerate")
    public Object regenerate(@PathVariable Long id, @PathVariable Long mid,
                             @AuthenticationPrincipal JwtPrincipal principal) {
        return chatStreamService.regenerate(id, mid, principal);
    }

    @PostMapping("/{id}/messages/{mid}/feedback")
    public ApiResponse<Void> feedback(@PathVariable Long id, @PathVariable Long mid,
                                      @RequestBody FeedbackRequest req,
                                      @AuthenticationPrincipal JwtPrincipal principal) {
        conversationService.feedback(id, mid, req.type(), req.comment(), principal);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/share")
    public ApiResponse<Map<String, Object>> share(@PathVariable Long id,
                                                  @RequestBody(required = false) ShareRequest req,
                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        LocalDateTime expiresAt = req != null && req.expiresAt() != null && !req.expiresAt().isBlank()
                ? LocalDateTime.parse(req.expiresAt(), DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        return ApiResponse.ok(conversationService.createShare(id, expiresAt, principal));
    }

    public record CreateRequest(@NotNull Long agentId, String title) {
    }

    public record SendRequest(@NotBlank String message, Boolean stream,
                              Map<String, Object> variables, List<Long> attachmentIds) {
    }

    public record FeedbackRequest(@NotBlank String type, String comment) {
    }

    public record ShareRequest(String expiresAt) {
    }
}
