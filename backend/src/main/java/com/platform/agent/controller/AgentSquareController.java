package com.platform.agent.controller;

import com.platform.agent.service.AgentSquareService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 广场接口（需求 3.8 / P1）：列表/详情/收藏/评价/举报/Fork/上下架。
 */
@RestController
@RequestMapping("/api/v1/square")
@RequiredArgsConstructor
public class AgentSquareController {

    private final AgentSquareService squareService;

    @GetMapping("/agents")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String tag,
                                                 @RequestParam(required = false) String sort,
                                                 @RequestParam(defaultValue = "false") boolean favoritesOnly,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.list(principal, keyword, category, tag, sort,
                favoritesOnly, page, pageSize));
    }

    @GetMapping("/agents/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.detail(id, principal));
    }

    @PostMapping("/agents/{id}/favorite")
    public ApiResponse<Map<String, Object>> favorite(@PathVariable Long id,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.toggleFavorite(id, principal));
    }

    @GetMapping("/favorites")
    public ApiResponse<Map<String, Object>> favorites(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.favorites(principal));
    }

    public record RateRequest(Integer score, String comment) {
    }

    @PostMapping("/agents/{id}/rating")
    public ApiResponse<Map<String, Object>> rate(@PathVariable Long id,
                                                 @RequestBody RateRequest req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.rate(id, req.score(), req.comment(), principal));
    }

    public record ReplyRequest(String reply) {
    }

    @PostMapping("/agents/{id}/ratings/{ratingId}/reply")
    public ApiResponse<Map<String, Object>> reply(@PathVariable Long id, @PathVariable Long ratingId,
                                                  @RequestBody ReplyRequest req,
                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.reply(id, ratingId, req.reply(), principal));
    }

    public record ReportRequest(String reason) {
    }

    @PostMapping("/agents/{id}/report")
    public ApiResponse<Void> report(@PathVariable Long id, @RequestBody ReportRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        squareService.report(id, req.reason(), principal);
        return ApiResponse.ok();
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<java.util.List<Map<String, Object>>> reports(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(squareService.reports(status));
    }

    public record HandleReportRequest(String status) {
    }

    @PutMapping("/reports/{reportId}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> handleReport(@PathVariable Long reportId,
                                          @RequestBody HandleReportRequest req,
                                          @AuthenticationPrincipal JwtPrincipal principal) {
        squareService.handleReport(reportId, req.status(), principal);
        return ApiResponse.ok();
    }

    @PostMapping("/agents/{id}/fork")
    @PreAuthorize("hasAuthority('agent:create')")
    public ApiResponse<Map<String, Object>> fork(@PathVariable Long id,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.fork(id, principal));
    }

    public record ListingRequest(boolean listed) {
    }

    @PutMapping("/agents/{id}/listing")
    @PreAuthorize("hasAuthority('agent:publish')")
    public ApiResponse<Map<String, Object>> listing(@PathVariable Long id, @RequestBody ListingRequest req,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(squareService.setListed(id, req.listed(), principal));
    }
}
