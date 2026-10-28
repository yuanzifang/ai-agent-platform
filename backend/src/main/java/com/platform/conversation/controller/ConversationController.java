package com.platform.conversation.controller;

import com.platform.common.response.ApiResponse;
import com.platform.conversation.service.ChatCheckpointService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

@io.swagger.v3.oas.annotations.tags.Tag(name = "会话对话")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatStreamService chatStreamService;
    private final ChatCheckpointService chatCheckpointService;

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

    /** 从部分 checkpoint 继续生成；非流式 best-effort。 */
    @PostMapping("/{id}/checkpoint/continue")
    public ApiResponse<Map<String, Object>> continueCheckpoint(@PathVariable Long id,
                                                               @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(chatStreamService.continueFromCheckpoint(id, principal));
    }

    /** 最新持久化 checkpoint；Redis live state 丢失时用于排查/回放。 */
    @GetMapping("/{id}/checkpoint")
    public ApiResponse<com.platform.conversation.entity.ChatCheckpoint> checkpoint(
            @PathVariable Long id,
            @AuthenticationPrincipal JwtPrincipal principal) {
        conversationService.owned(id, principal);
        return ApiResponse.ok(chatCheckpointService.get(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        conversationService.delete(id, principal);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @RequestBody UpdateRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        conversationService.update(id, req.title(), req.pinned(), principal);
        return ApiResponse.ok();
    }

    /** 发送消息：stream=true 返回 SseEmitter（自动 text/event-stream），false 返回 JSON（7.3）。 */
    @PostMapping("/{id}/messages")
    public Object send(@PathVariable Long id,
                       @RequestBody SendRequest req,
                       @RequestHeader(value = "X-Gray-Tag", required = false) String grayTagHeader,
                       @AuthenticationPrincipal JwtPrincipal principal) {
        // 灰度标记约定走请求头（与网关/开放 API 一致），不进请求体
        Object result = chatStreamService.send(id, new ChatStreamService.SendRequest(
                req.message(), req.stream(), req.variables(), req.attachmentIds(),
                req.allowWriteTools(), ChatStreamService.sanitizeGrayTag(grayTagHeader)), principal);
        return result instanceof SseEmitter ? result : ApiResponse.ok(result);
    }

    /** 断线续传：offset 为客户端已收到的字符数，返回剩余增量直到 done。 */
    @GetMapping(value = "/{id}/resume", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable Long id,
                             @RequestParam(defaultValue = "0") int offset,
                             @AuthenticationPrincipal JwtPrincipal principal) {
        return chatStreamService.resume(id, offset, principal);
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

    public record UpdateRequest(String title, Boolean pinned) {
    }

    public record SendRequest(@NotBlank String message, Boolean stream,
                              Map<String, Object> variables, List<Long> attachmentIds,
                              Boolean allowWriteTools) {
    }

    public record FeedbackRequest(@NotBlank String type, String comment) {
    }

    public record ShareRequest(String expiresAt) {
    }
}
