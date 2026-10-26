package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 技能脚本执行：Python / JavaScript 隔离执行（临时目录、超时、输出上限）。
 * 参数通过 SKILL_ARGS 环境变量与 stdin 传入，脚本内可直接使用 params/args；stdout 即工具结果。
 * 隔离层级由 skill.script.runtime 选择：
 * - process（默认）：进程级（OS 沙箱 + 语言级禁网兜底），适合本机开发；
 * - docker：容器级沙箱（--network none / 内存/CPU/PID 限额 / cap-drop ALL / tmpfs），
 *   生产或 SaaS 开启脚本时使用；要求后端可访问 docker CLI（本机模式，或容器内挂载 socket+CLI）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptRunnerService {

    private final ObjectMapper objectMapper;

    @Value("${skill.script.enabled:true}")
    private boolean enabled;

    @Value("${skill.script.python-command:python3}")
    private String pythonCommand;

    @Value("${skill.script.node-command:node}")
    private String nodeCommand;

    @Value("${skill.script.max-output-bytes:65536}")
    private int maxOutputBytes;

    @Value("${skill.script.max-code-length:50000}")
    private int maxCodeLength;

    @Value("${skill.script.network-isolation:true}")
    private boolean networkIsolation;

    /** 隔离层级：process（默认，进程级）/ docker（容器级沙箱）。 */
    @Value("${skill.script.runtime:process}")
    private String runtime;

    @Value("${skill.script.docker-bin:docker}")
    private String dockerBin;

    @Value("${skill.script.docker.python-image:python:3.12-alpine}")
    private String dockerPythonImage;

    @Value("${skill.script.docker.node-image:node:22-alpine}")
    private String dockerNodeImage;

    @Value("${skill.script.docker.memory:256m}")
    private String dockerMemory;

    @Value("${skill.script.docker.cpus:0.5}")
    private String dockerCpus;

    @Value("${skill.script.docker.pids-limit:64}")
    private int dockerPidsLimit;

    private volatile Boolean unshareUsable;
    private volatile Boolean dockerUsable;

    public record Result(String output, String stderr, int exitCode, long durationMs, boolean truncated) {
    }

    private record Capped(String text, boolean truncated) {
    }

    public Result run(String language, String code, Map<String, Object> args, int timeoutMs) {
        return run(language, code, args, timeoutMs, () -> false);
    }

    /** cancelled 由对话停止信号驱动：每 100ms 轮询，取消时强杀子进程。 */
    public Result run(String language, String code, Map<String, Object> args, int timeoutMs,
                      BooleanSupplier cancelled) {
        if (!enabled) throw new BusinessException(403, "技能脚本执行未启用（SKILL_SCRIPT_ENABLED=false）");
        String lang = normalizeLanguage(language);
        if (code == null || code.isBlank()) throw new BusinessException(400, "脚本代码不能为空");
        if (code.length() > maxCodeLength) {
            throw new BusinessException(400, "脚本代码过长（上限 " + maxCodeLength + " 字符）");
        }
        int timeout = Math.max(500, Math.min(timeoutMs <= 0 ? 5000 : timeoutMs, 60000));
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            throw new BusinessException(400, "脚本参数不是合法 JSON");
        }
        String fullCode = prelude(lang) + code;

        Path dir = null;
        String containerName = null;
        String dockerBinPath = null;
        long start = System.currentTimeMillis();
        try {
            dir = Files.createTempDirectory("skill-script-");
            Path file = dir.resolve("PYTHON".equals(lang) ? "main.py" : "main.js");
            Files.writeString(file, fullCode, StandardCharsets.UTF_8);
            boolean containerMode = "docker".equals(runtime);
            if (containerMode) {
                dockerBinPath = findExecutable(dockerBin);
                if (dockerBinPath == null || !dockerUsable(dockerBinPath)) {
                    log.warn("skill.script.runtime=docker 但 Docker 不可用，回退进程级隔离（网络仍受 OS/语言级沙箱限制）");
                    containerMode = false;
                }
            }
            List<String> command;
            if (containerMode) {
                containerName = "skill-" + UUID.randomUUID().toString().replace("-", "");
                command = dockerCommand(dockerBinPath, lang, fullCode, argsJson, containerName);
            } else {
                String executable = "PYTHON".equals(lang)
                        ? resolveExecutable(pythonCommand, "python3")
                        : resolveExecutable(nodeCommand, "node");
                command = "PYTHON".equals(lang)
                        ? List.of(executable, "-u", file.toString())
                        : List.of(executable, file.toString());
                if (networkIsolation) command = networkSandbox(command);
            }
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
            if (containerMode) {
                // 参数经 -e 注入容器；docker CLI 自身保持宿主机环境（PATH 等）
            } else {
                pb.environment().clear();
                String path = System.getenv().getOrDefault("PATH", "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin");
                pb.environment().put("PATH", path);
                pb.environment().put("HOME", dir.toString());
                pb.environment().put("TMPDIR", dir.toString());
                pb.environment().put("TEMP", dir.toString());
                pb.environment().put("TMP", dir.toString());
                pb.environment().put("LANG", "en_US.UTF-8");
                pb.environment().put("LC_ALL", "en_US.UTF-8");
                pb.environment().put("SKILL_ARGS", argsJson);
                if ("PYTHON".equals(lang)) {
                    pb.environment().put("PYTHONIOENCODING", "UTF-8");
                    pb.environment().put("PYTHONUNBUFFERED", "1");
                    pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
                }
            }
            Process process = pb.start();
            if (!containerMode) {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(argsJson.getBytes(StandardCharsets.UTF_8));
                }
            }

            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Capped> stdoutFuture = pool.submit(() -> readCapped(process.getInputStream(), maxOutputBytes));
            Future<Capped> stderrFuture = pool.submit(() -> readCapped(process.getErrorStream(), 4000));
            long deadline = System.currentTimeMillis() + timeout;
            try {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) {
                        process.destroyForcibly();
                        if (containerName != null) dockerRmF(dockerBinPath, containerName);
                        pool.shutdownNow();
                        throw new BusinessException(499, "脚本执行已取消");
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        process.destroyForcibly();
                        process.waitFor(2, TimeUnit.SECONDS);
                        if (containerName != null) dockerRmF(dockerBinPath, containerName);
                        pool.shutdownNow();
                        throw new BusinessException(504, "脚本执行超时（" + timeout + "ms）");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                if (containerName != null) dockerRmF(dockerBinPath, containerName);
                throw new BusinessException(500, "脚本执行被中断");
            }
            Capped stdout = stdoutFuture.get(3, TimeUnit.SECONDS);
            Capped stderr = stderrFuture.get(3, TimeUnit.SECONDS);
            pool.shutdownNow();
            long duration = System.currentTimeMillis() - start;
            String output = stdout.text().strip();
            String err = stderr.text().strip();
            if (stdout.truncated()) output = output + "\n…（输出已截断）";
            if (process.exitValue() != 0) {
                String detail = !err.isBlank() ? err : (!output.isBlank() ? output : "无错误输出");
                throw new BusinessException(500, "脚本执行失败（exit " + process.exitValue() + "）："
                        + truncate(detail, 1200));
            }
            return new Result(output, err, process.exitValue(), duration, stdout.truncated());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("技能脚本执行异常：{}", e.getMessage());
            throw new BusinessException(500, "脚本执行失败：" + rootMessage(e));
        } finally {
            if (containerName != null) dockerRmF(dockerBinPath, containerName);
            if (dir != null) deleteRecursively(dir);
        }
    }

    /** 根据技能参数定义生成工具 inputSchema。 */
    public String inputSchema(Object params) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map<String, Object> def : paramDefs(params)) {
            String name = str(def.get("key"));
            if (name == null || name.isBlank()) name = str(def.get("name"));
            if (name == null || name.isBlank()) continue;
            Map<String, Object> prop = new LinkedHashMap<>();
            String type = str(def.get("type"));
            prop.put("type", type == null || type.isBlank() ? "string" : type);
            if (def.get("description") != null) prop.put("description", def.get("description"));
            if (def.get("defaultValue") != null) prop.put("default", def.get("defaultValue"));
            props.put(name, prop);
            if (Boolean.TRUE.equals(def.get("required")) || "true".equals(String.valueOf(def.get("required")))) {
                required.add(name);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    /** 把技能参数默认值合并进调用参数（LLM 未传时使用默认值）。 */
    public Map<String, Object> withDefaults(Object params, Map<String, Object> args) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map<String, Object> def : paramDefs(params)) {
            String key = str(def.get("key"));
            if (key == null || key.isBlank()) key = str(def.get("name"));
            if (key != null && def.get("defaultValue") != null) merged.put(key, def.get("defaultValue"));
        }
        if (args != null) merged.putAll(args);
        return merged;
    }

    public boolean enabled() {
        return enabled;
    }

    private List<Map<String, Object>> paramDefs(Object params) {
        Object parsed = params;
        if (params instanceof String s) {
            if (s.isBlank()) return List.of();
            try {
                parsed = objectMapper.readValue(s, Object.class);
            } catch (Exception e) {
                return List.of();
            }
        }
        if (!(parsed instanceof List<?> list)) return List.of();
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> def = new LinkedHashMap<>();
                m.forEach((k, v) -> def.put(String.valueOf(k), v));
                defs.add(def);
            }
        }
        return defs;
    }

    private String normalizeLanguage(String language) {
        String lang = language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
        if (lang.isBlank() || "NONE".equals(lang)) throw new BusinessException(400, "请选择脚本语言");
        if (!List.of("PYTHON", "JAVASCRIPT", "JS").contains(lang)) {
            throw new BusinessException(400, "不支持的脚本语言：" + language);
        }
        return "JS".equals(lang) ? "JAVASCRIPT" : lang;
    }

    private String prelude(String lang) {
        if ("PYTHON".equals(lang)) {
            return "import os, json\n"
                    + "params = json.loads(os.environ.get(\"SKILL_ARGS\") or \"{}\")\n"
                    + "args = params\n"
                    // 语言级兜底（OS 沙箱不可用时）：禁用 socket / DNS
                    + "try:\n"
                    + "    import socket\n"
                    + "    def _net_denied(*a, **k):\n"
                    + "        raise PermissionError(\"network access disabled by skill sandbox\")\n"
                    + "    socket.socket = _net_denied\n"
                    + "    socket.create_connection = _net_denied\n"
                    + "    socket.getaddrinfo = _net_denied\n"
                    + "except Exception:\n"
                    + "    pass\n";
        }
        return "const params = JSON.parse(process.env.SKILL_ARGS || \"{}\");\n"
                + "const args = params;\n"
                + "try {\n"
                + "  const net = require('net'); const dns = require('dns');\n"
                + "  const deny = () => { throw new Error('network access disabled by skill sandbox'); };\n"
                + "  net.Socket.prototype.connect = deny; net.connect = deny; net.createConnection = deny;\n"
                + "  dns.lookup = deny; dns.resolve = deny; dns.resolve4 = deny;\n"
                + "} catch (e) {}\n";
    }

    /** OS 级网络隔离：macOS sandbox-exec / Linux unshare -n（不可用时回退语言级禁用）。 */
    private List<String> networkSandbox(List<String> command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            File sandbox = new File("/usr/bin/sandbox-exec");
            if (sandbox.canExecute()) {
                List<String> wrapped = new ArrayList<>();
                wrapped.add(sandbox.getAbsolutePath());
                wrapped.add("-p");
                wrapped.add("(version 1)(allow default)(deny network*)");
                wrapped.addAll(command);
                return wrapped;
            }
        }
        if (os.contains("linux")) {
            File unshare = new File("/usr/bin/unshare");
            if (unshare.canExecute() && unshareUsable()) {
                List<String> wrapped = new ArrayList<>();
                wrapped.add(unshare.getAbsolutePath());
                wrapped.add("-n");
                wrapped.add("--");
                wrapped.addAll(command);
                return wrapped;
            }
        }
        log.warn("脚本网络沙箱不可用，回退到语言级 socket 禁用（可被绕过，生产建议容器隔离）");
        return command;
    }

    private boolean unshareUsable() {
        Boolean cached = unshareUsable;
        if (cached != null) return cached;
        try {
            Process p = new ProcessBuilder("/usr/bin/unshare", "-n", "--", "true")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            unshareUsable = ok;
            return ok;
        } catch (Exception e) {
            unshareUsable = false;
            return false;
        }
    }

    /**
     * 容器沙箱命令：无网络、资源限额、丢弃全部 capability、禁提权、/tmp 用 noexec tmpfs。
     * 脚本经 -c/-e 参数直传（不挂载宿主路径——macOS 默认不共享临时目录，且避免宿主路径泄漏容器）。
     * package-private 供单测断言命令构造。
     */
    List<String> dockerCommand(String dockerBinPath, String lang, String fullCode, String argsJson, String name) {
        List<String> c = new ArrayList<>(List.of(
                dockerBinPath, "run", "--rm", "--name", name,
                "--network", "none",
                "--memory", dockerMemory, "--cpus", dockerCpus,
                "--pids-limit", String.valueOf(dockerPidsLimit),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--tmpfs", "/tmp:rw,size=33554432,noexec,nosuid",
                "-e", "SKILL_ARGS=" + argsJson,
                "-e", "LANG=C.UTF-8"));
        if ("PYTHON".equals(lang)) {
            c.addAll(List.of("-e", "PYTHONIOENCODING=utf-8", "-e", "PYTHONUNBUFFERED=1",
                    "-e", "PYTHONDONTWRITEBYTECODE=1",
                    dockerPythonImage, "python3", "-u", "-c", fullCode));
        } else {
            c.addAll(List.of(dockerNodeImage, "node", "-e", fullCode));
        }
        return c;
    }

    private boolean dockerUsable(String dockerBinPath) {
        Boolean cached = dockerUsable;
        if (cached != null) return cached;
        try {
            Process p = new ProcessBuilder(dockerBinPath, "info").redirectErrorStream(true).start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            dockerUsable = ok;
            if (!ok) log.debug("docker info 失败：{}", new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            return ok;
        } catch (Exception e) {
            dockerUsable = false;
            return false;
        }
    }

    /** 强制清理容器（超时/取消/兜底）；rm -f 幂等，失败仅记日志。 */
    private void dockerRmF(String dockerBinPath, String name) {
        if (dockerBinPath == null) return;
        try {
            Process p = new ProcessBuilder(dockerBinPath, "rm", "-f", name)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) {
            log.debug("容器清理失败 {}：{}", name, e.getMessage());
        }
    }

    private String resolveExecutable(String configured, String name) {
        String resolved = findExecutable(configured);
        if (resolved != null) return resolved;
        resolved = findExecutable(name);
        if (resolved != null) return resolved;
        if ("node".equals(name)) {
            File home = new File(System.getProperty("user.home", ""), "tools");
            File[] dirs = home.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (d.isDirectory() && d.getName().startsWith("node-")) {
                        File node = new File(d, "bin/node");
                        if (node.canExecute()) return node.getAbsolutePath();
                    }
                }
            }
            for (String candidate : List.of("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")) {
                if (new File(candidate).canExecute()) return candidate;
            }
        } else {
            for (String candidate : List.of("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3")) {
                if (new File(candidate).canExecute()) return candidate;
            }
        }
        throw new BusinessException(500, "未找到脚本运行环境（" + name + "），请配置 skill.script 命令");
    }

    private String findExecutable(String command) {
        if (command == null || command.isBlank()) return null;
        File direct = new File(command);
        if (direct.isAbsolute()) return direct.canExecute() ? direct.getAbsolutePath() : null;
        String path = System.getenv().getOrDefault("PATH", "");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            File f = new File(dir, command);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private Capped readCapped(InputStream in, int cap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        boolean truncated = false;
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (total < cap) {
                    int take = Math.min(n, cap - total);
                    out.write(buf, 0, take);
                    total += take;
                    if (take < n) truncated = true;
                } else {
                    truncated = true;
                }
            }
        } catch (Exception e) {
            // 进程被超时终止时流会关闭，忽略读取异常
        }
        return new Capped(out.toString(StandardCharsets.UTF_8), truncated);
    }

    private void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception e) {     log.debug("脚本临时目录清理失败：{}", e.getMessage()); }
            });
        } catch (Exception e) {
            log.debug("脚本临时目录清理失败：{}", e.getMessage());
        }
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
