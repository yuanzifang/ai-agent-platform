package com.platform.deploy.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通过 docker CLI 操作本机容器（MVP：本机 Docker）。
 * 用 CLI 而不是 docker-java，避免引入大依赖；命令参数用 ProcessBuilder 分离，无 shell 注入风险。
 */
@Slf4j
@Component
public class DockerCliRuntime implements ContainerRuntime {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${agent.deploy.docker-bin:docker}")
    private String dockerBin;

    @Override
    public boolean available() {
        return version() != null;
    }

    @Override
    public String version() {
        try {
            return exec(List.of(binary(), "info", "--format", "{{.ServerVersion}}"), 10);
        } catch (Exception e) {
            log.warn("Docker 检测失败（docker-bin={}）：{}", dockerBin, e.getMessage());
            return null;
        }
    }

    @Override
    public String create(CreateRequest request) {
        List<String> cmd = new ArrayList<>(List.of(binary(), "create",
                "--name", request.name(),
                "--restart", "unless-stopped"));
        request.labels().forEach((k, v) -> {
            cmd.add("--label");
            cmd.add(k + "=" + v);
        });
        cmd.addAll(List.of("-p", request.hostBind() + ":" + request.hostPort() + ":" + request.containerPort()));
        // 容器内访问宿主平台（Docker Desktop 自带 host.docker.internal；Linux 用 host-gateway 映射）
        cmd.addAll(List.of("--add-host", "host.docker.internal:host-gateway"));
        cmd.addAll(List.of("--health-cmd", "wget -q -O- http://127.0.0.1:" + request.containerPort() + "/healthz || exit 1",
                "--health-interval", "10s", "--health-retries", "3", "--health-timeout", "3s"));
        request.env().forEach((k, v) -> {
            cmd.add("-e");
            cmd.add(k + "=" + v);
        });
        for (String mount : request.mounts()) {
            cmd.add("-v");
            cmd.add(mount);
        }
        cmd.addAll(request.extraArgs());
        cmd.add(request.image());
        return exec(cmd, 120);
    }

    @Override
    public void start(String container) {
        exec(List.of(binary(), "start", container), 60);
    }

    @Override
    public void stop(String container) {
        exec(List.of(binary(), "stop", container), 60);
    }

    @Override
    public void remove(String container) {
        exec(List.of(binary(), "rm", "-f", container), 60);
    }

    @Override
    public Info inspect(String container) {
        String raw = exec(List.of(binary(), "inspect", "--format", "{{json .State}}", container), 20);
        try {
            JsonNode state = JSON.readTree(raw);
            String status = state.path("Status").asText("");
            return new Info(container, state.path("Running").asBoolean(false), status,
                    state.path("Error").asText(""));
        } catch (Exception e) {
            throw new BusinessException(502, "解析容器状态失败：" + e.getMessage());
        }
    }

    @Override
    public String logs(String container, int tail) {
        return exec(List.of(binary(), "logs", "--tail", String.valueOf(Math.max(1, tail)), container), 30);
    }

    /**
     * 解析 docker 可执行文件：IDE/launchd 启动的后端 PATH 往往不含 Homebrew，
     * 因此 "docker" 找不到时回退到常见绝对路径。
     */
    private String binary() {
        if (dockerBin != null && dockerBin.contains("/")) return dockerBin;
        String name = (dockerBin == null || dockerBin.isBlank()) ? "docker" : dockerBin;
        for (String dir : List.of("/usr/local/bin", "/opt/homebrew/bin", System.getProperty("user.home") + "/.docker/bin")) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return name;
    }

    private String exec(List<String> args, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException(409, "Docker 命令超时：" + String.join(" ", args));
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(409, "Docker 命令失败：" + abbreviate(output));
            }
            return output;
        } catch (IOException e) {
            throw new BusinessException(409,
                    "无法执行 Docker（" + dockerBin + "），请确认本机已安装并启动 Docker：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "Docker 命令被中断");
        }
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
