package com.platform.search;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 顶栏全局搜索统一入口；权限沿用各模块现有查询。 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public ApiResponse<Map<String, Object>> search(@RequestParam("q") String query,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(searchService.search(query, principal));
    }

    /** 记录结果点击，用于轻量相关性反馈；失败不阻断前端跳转。 */
    @PostMapping("/feedback")
    public ApiResponse<Map<String, Object>> feedback(@Valid @RequestBody FeedbackRequest req,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        searchService.feedback(req.type(), req.id(), req.query(), principal);
        return ApiResponse.ok(Map.of("recorded", true));
    }

    public record FeedbackRequest(@NotBlank String type, @NotBlank String id, String query) {
    }
}
