package com.platform.deploy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.deploy.entity.AgentDeployProfile;
import com.platform.deploy.mapper.AgentDeployProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Agent 独立部署资源 Profile 的平台管理与运行时解析。 */
@Service
@RequiredArgsConstructor
public class AgentDeployProfileService {

    private static final Pattern CPU = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Pattern MEMORY = Pattern.compile("^\\d+[mMgG]$");

    private final AgentDeployProfileMapper mapper;

    public List<AgentDeployProfile> list(boolean enabledOnly) {
        return mapper.selectList(new LambdaQueryWrapper<AgentDeployProfile>()
                .eq(enabledOnly, AgentDeployProfile::getStatus, "ENABLED")
                .orderByAsc(AgentDeployProfile::getId));
    }

    public AgentDeployProfile get(Long id) {
        AgentDeployProfile p = mapper.selectById(id);
        if (p == null) throw new BusinessException(404, "部署资源 Profile 不存在");
        return p;
    }

    /**
     * 指定 id 时校验存在且启用；未指定时使用第一个启用 Profile。
     * 返回 null 表示尚未配置 Profile，部署继续使用全局 application.yml 默认值。
     */
    public AgentDeployProfile resolve(Long id) {
        if (id == null) {
            return mapper.selectOne(new LambdaQueryWrapper<AgentDeployProfile>()
                    .eq(AgentDeployProfile::getStatus, "ENABLED")
                    .orderByAsc(AgentDeployProfile::getId)
                    .last("LIMIT 1"));
        }
        AgentDeployProfile p = get(id);
        if (!"ENABLED".equalsIgnoreCase(p.getStatus())) {
            throw new BusinessException(400, "部署资源 Profile 已停用：" + p.getName());
        }
        return p;
    }

    public AgentDeployProfile create(Map<String, Object> req) {
        AgentDeployProfile p = new AgentDeployProfile();
        apply(p, req);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.insert(p);
        return p;
    }

    public AgentDeployProfile update(Long id, Map<String, Object> req) {
        AgentDeployProfile p = get(id);
        apply(p, req);
        p.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(p);
        return p;
    }

    public void delete(Long id) {
        get(id);
        mapper.deleteById(id);
    }

    private void apply(AgentDeployProfile p, Map<String, Object> req) {
        String name = value(req.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "部署资源 Profile 名称必填");
        String cpu = value(req.get("cpuLimit"));
        if (cpu == null || !CPU.matcher(cpu).matches() || Double.parseDouble(cpu) <= 0) {
            throw new BusinessException(400, "CPU 限制格式错误，例如 0.5 或 1");
        }
        String memory = value(req.get("memoryLimit"));
        if (memory == null || !MEMORY.matcher(memory).matches()) {
            throw new BusinessException(400, "内存限制格式错误，例如 512m 或 1g");
        }
        int pids = integer(req.get("pidsLimit"), 256);
        if (pids < 16 || pids > 4096) throw new BusinessException(400, "PID 上限应在 16-4096");
        int maxReplicas = integer(req.get("maxReplicas"), 5);
        if (maxReplicas < 1 || maxReplicas > 5) throw new BusinessException(400, "最大副本数应在 1-5");
        int defaultReplicas = integer(req.get("defaultReplicas"), 1);
        if (defaultReplicas < 1 || defaultReplicas > maxReplicas) {
            throw new BusinessException(400, "默认副本数应在 1-" + maxReplicas);
        }
        p.setName(name);
        p.setDescription(value(req.get("description")));
        p.setCpuLimit(cpu);
        p.setMemoryLimit(memory.toLowerCase(Locale.ROOT));
        p.setPidsLimit(pids);
        p.setDefaultReplicas(defaultReplicas);
        p.setMaxReplicas(maxReplicas);
        p.setStatus("DISABLED".equalsIgnoreCase(value(req.get("status"))) ? "DISABLED" : "ENABLED");
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String value(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
