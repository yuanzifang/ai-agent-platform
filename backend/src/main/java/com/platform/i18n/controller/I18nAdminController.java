package com.platform.i18n.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import com.platform.i18n.entity.I18nMessage;
import com.platform.i18n.service.I18nService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 国际化管理（平台管理员）：键值编辑/删除、CSV/JSON 导入导出。
 * 资源为平台级全局（跨租户共享），故按平台管理员而非租户权限控制。
 */
@RestController
@RequestMapping("/api/v1/i18n/admin")
@RequiredArgsConstructor
public class I18nAdminController {

    private final I18nService i18nService;

    @GetMapping("/messages")
    public ApiResponse<Page<I18nMessage>> page(
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            JwtPrincipal principal) {
        requirePlatformAdmin(principal);
        return ApiResponse.ok(i18nService.page(lang, keyword, page, size));
    }

    @PostMapping("/messages")
    public ApiResponse<Map<String, Object>> upsert(@RequestBody UpsertRequest req,
                                                   JwtPrincipal principal) {
        requirePlatformAdmin(principal);
        i18nService.upsert(req.lang(), req.key(), req.content(), req.description(),
                principal == null ? null : principal.userId());
        return ApiResponse.ok(Map.of("saved", true));
    }

    @DeleteMapping("/messages/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id, JwtPrincipal principal) {
        requirePlatformAdmin(principal);
        i18nService.delete(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importMessages(@RequestBody ImportRequest req,
                                                           JwtPrincipal principal) {
        requirePlatformAdmin(principal);
        return ApiResponse.ok(i18nService.importMessages(req.lang(), req.format(),
                req.content(), principal == null ? null : principal.userId()));
    }

    /** 导出（前端以 Blob 下载）：format=csv 带 BOM 供 Excel 直开，json 为扁平键值。 */
    @GetMapping("/export")
    public ExportResponse export(@RequestParam String lang,
                                 @RequestParam(defaultValue = "json") String format,
                                 @RequestParam(required = false) String filename,
                                 JwtPrincipal principal) {
        requirePlatformAdmin(principal);
        String fmt = "csv".equalsIgnoreCase(format) ? "csv" : "json";
        byte[] data = i18nService.export(lang, fmt);
        String name = (filename == null || filename.isBlank()
                ? "i18n-" + i18nService.messages(lang).size() + "-" + lang
                : filename) + "." + fmt;
        return new ExportResponse(name, data);
    }

    /** 二进制导出绕过统一 ApiResponse 包装（文件下载语义）。 */
    public record ExportResponse(String filename, byte[] data) {
    }

    private void requirePlatformAdmin(JwtPrincipal principal) {
        if (principal == null || !principal.platformAdmin()) {
            throw new BusinessException(403, "仅平台管理员可管理国际化字段");
        }
    }

    public record UpsertRequest(String lang, String key, String content, String description) {
    }

    public record ImportRequest(String lang, String format, String content) {
    }
}
