package com.platform.observability.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.observability.entity.SystemLog;
import com.platform.observability.mapper.SystemLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 运行日志查询（租户条件由 TenantLineInnerInterceptor 自动追加）。 */
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private final SystemLogMapper mapper;

    public Map<String, Object> query(String level, String keyword, long page, long pageSize) {
        long size = Math.min(Math.max(pageSize, 1), 200);
        LambdaQueryWrapper<SystemLog> qw = new LambdaQueryWrapper<>();
        if (level != null && !level.isBlank()) {
            qw.eq(SystemLog::getLevel, level.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like(SystemLog::getMessage, k).or().like(SystemLog::getLogger, k));
        }
        qw.orderByDesc(SystemLog::getCreatedAt).orderByDesc(SystemLog::getId);
        Page<SystemLog> result = mapper.selectPage(new Page<>(Math.max(page, 1), size), qw);
        return Map.of("list", result.getRecords(), "total", result.getTotal(),
                "page", Math.max(page, 1), "pageSize", size);
    }
}
