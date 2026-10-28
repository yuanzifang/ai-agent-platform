package com.platform.deploy.service;

import com.platform.deploy.entity.AgentDeployProfile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把 Agent 部署 Profile 映射成 Kubernetes container resources。 */
@Service
public class K8sResourceMapper {

    public Map<String, Object> resources(AgentDeployProfile profile) {
        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("cpu", cpu(profile.getCpuRequest(), "250m"));
        requests.put("memory", memory(profile.getMemoryRequest(), "256Mi"));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("cpu", cpu(profile.getCpuLimit(), "1"));
        limits.put("memory", memory(profile.getMemoryLimit(), "512Mi"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", requests);
        out.put("limits", limits);
        return out;
    }

    String cpu(String raw, String fallback) {
        String text = raw == null || raw.isBlank() ? fallback : raw.trim();
        if (text.endsWith("m")) return text;
        double value = Double.parseDouble(text);
        if (value < 1) return Math.round(value * 1000) + "m";
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return Math.round(value * 1000) + "m";
    }

    String memory(String raw, String fallback) {
        String text = (raw == null || raw.isBlank() ? fallback : raw.trim()).toLowerCase();
        if (text.endsWith("mi") || text.endsWith("gi")) {
            return text.substring(0, text.length() - 2)
                    + Character.toUpperCase(text.charAt(text.length() - 2))
                    + text.substring(text.length() - 1);
        }
        if (text.endsWith("m")) return text.substring(0, text.length() - 1) + "Mi";
        if (text.endsWith("g")) return text.substring(0, text.length() - 1) + "Gi";
        return text + "Mi";
    }
}
