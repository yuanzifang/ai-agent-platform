package com.platform.i18n.controller;

import com.platform.common.response.ApiResponse;
import com.platform.i18n.service.I18nService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 国际化读取（公开）：登录页等匿名场景即需语言包，故 GET 放行；
 * 前端启动/切换语言时拉取扁平 {key: value} 并 mergeLocaleMessage 覆盖静态包。
 */
@RestController
@RequestMapping("/api/v1/i18n")
@RequiredArgsConstructor
public class I18nMessageController {

    private static final Set<String> BASE_LOCALES = new LinkedHashSet<>(List.of("zh-CN", "en-US"));

    private final I18nService i18nService;

    /** 数据库已配置的语言 ∪ 前端静态包自带语言，排序返回。 */
    @GetMapping("/locales")
    public ApiResponse<List<String>> locales() {
        Set<String> locales = new LinkedHashSet<>(BASE_LOCALES);
        locales.addAll(i18nService.locales());
        return ApiResponse.ok(List.copyOf(locales));
    }

    /** 扁平键值映射（点号分层键，如 menu.agents）；缓存 1 分钟，写操作即时失效。 */
    @GetMapping("/messages/{lang}")
    public ApiResponse<Map<String, String>> messages(@PathVariable String lang) {
        return ApiResponse.ok(i18nService.messages(lang));
    }
}
