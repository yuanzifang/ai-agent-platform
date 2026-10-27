package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.system.entity.ResourceProfile;
import com.platform.system.mapper.ResourceProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 平台检索资源组合；Agent 只引用 profileId。 */
@Service
@RequiredArgsConstructor
public class ResourceProfileService {

    private static final Set<String> SEARCH = Set.of("MYSQL", "ES");
    private static final Set<String> VECTOR = Set.of("PGVECTOR", "QDRANT");
    private final ResourceProfileMapper mapper;

    public List<ResourceProfile> list(boolean enabledOnly) {
        return mapper.selectList(new LambdaQueryWrapper<ResourceProfile>()
                .eq(enabledOnly, ResourceProfile::getStatus, "ENABLED")
                .orderByAsc(ResourceProfile::getName));
    }

    public ResourceProfile get(Long id) {
        ResourceProfile p = mapper.selectById(id);
        if (p == null) throw new BusinessException(404, "资源 Profile 不存在");
        return p;
    }

    public ResourceProfile create(Map<String, Object> req) {
        ResourceProfile p = new ResourceProfile();
        apply(p, req);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.insert(p);
        return p;
    }

    public ResourceProfile update(Long id, Map<String, Object> req) {
        ResourceProfile p = get(id);
        apply(p, req);
        p.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(p);
        return p;
    }

    public void delete(Long id) {
        get(id);
        mapper.deleteById(id);
    }

    private void apply(ResourceProfile p, Map<String, Object> req) {
        String name = value(req.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "Profile 名称必填");
        String search = upper(req.get("searchBackend"));
        String vector = upper(req.get("vectorBackend"));
        if (!SEARCH.contains(search)) throw new BusinessException(400, "关键字后端必须是 MYSQL/ES");
        if (!VECTOR.contains(vector)) throw new BusinessException(400, "向量后端必须是 PGVECTOR/QDRANT");
        p.setName(name);
        p.setDescription(value(req.get("description")));
        p.setSearchBackend(search);
        p.setVectorBackend(vector);
        p.setStatus("DISABLED".equalsIgnoreCase(value(req.get("status"))) ? "DISABLED" : "ENABLED");
    }

    private String upper(Object value) {
        return value == null ? "" : String.valueOf(value).toUpperCase(Locale.ROOT);
    }

    private String value(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
