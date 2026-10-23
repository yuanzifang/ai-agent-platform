package com.platform.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysConfig;
import com.platform.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台系统设置（需求 3.8）：sys_config KV，仅平台管理员。
 */
@RestController
@RequestMapping("/api/v1/admin/config")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "系统设置")
public class SysConfigController {

    private final SysConfigMapper configMapper;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal JwtPrincipal principal) {
        if (!principal.platformAdmin()) {
            throw new com.platform.common.exception.BusinessException(403, "仅平台管理员可访问");
        }
        List<SysConfig> rows = configMapper.selectList(
                new LambdaQueryWrapper<SysConfig>().orderByAsc(SysConfig::getConfigKey));
        return ApiResponse.ok(rows.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configKey", c.getConfigKey());
            m.put("configValue", c.getConfigValue());
            m.put("description", c.getDescription());
            return m;
        }).toList());
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody UpdateRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        if (!principal.platformAdmin()) {
            throw new com.platform.common.exception.BusinessException(403, "仅平台管理员可访问");
        }
        SysConfig existing = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, req.configKey()));
        if (existing == null) {
            throw new com.platform.common.exception.BusinessException(404, "配置项不存在");
        }
        SysConfig patch = new SysConfig();
        patch.setId(existing.getId());
        patch.setConfigValue(req.configValue());
        configMapper.updateById(patch);
        return ApiResponse.ok();
    }

    public record UpdateRequest(String configKey, String configValue) {
    }
}
