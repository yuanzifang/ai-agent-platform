package com.platform.system.service;

import com.platform.common.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 平台基础设施配置。保存到本地 YAML，重启后端生效；不把连接凭据暴露给 Agent。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformInfraConfigService {

    private final Environment env;
    private final CryptoService cryptoService;

    public String configPath() {
        return Path.of(System.getenv().getOrDefault("PLATFORM_INFRA_CONFIG",
                "config/application-platform.yml")).toAbsolutePath().normalize().toString();
    }

    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configFile", configPath());
        out.put("restartRequired", false);
        out.put("mysqlUrl", env.getProperty("spring.datasource.url", ""));
        out.put("mysqlUsername", env.getProperty("spring.datasource.username", ""));
        out.put("redisHost", env.getProperty("spring.data.redis.host", ""));
        out.put("redisPort", env.getProperty("spring.data.redis.port", "6379"));
        out.put("pgUrl", env.getProperty("pgvector.url", ""));
        out.put("pgUsername", env.getProperty("pgvector.username", ""));
        out.put("minioEndpoint", env.getProperty("storage.minio.endpoint", ""));
        out.put("minioAccessKey", env.getProperty("storage.minio.access-key", ""));
        out.put("minioBucket", env.getProperty("storage.minio.bucket", "ai-agent-platform"));
        out.put("esEnabled", Boolean.parseBoolean(env.getProperty("search.es.enabled", "false")));
        out.put("esUrl", env.getProperty("search.es.url", ""));
        out.put("esIndex", env.getProperty("search.es.index", ""));
        out.put("esUsername", env.getProperty("search.es.username", ""));
        out.put("qdrantEnabled", Boolean.parseBoolean(env.getProperty("vector.qdrant.enabled", "false")));
        out.put("qdrantUrl", env.getProperty("vector.qdrant.url", ""));
        out.put("qdrantCollectionPrefix", env.getProperty("vector.qdrant.collection-prefix", "aap"));
        out.put("mysqlPasswordSet", has(env.getProperty("spring.datasource.password")));
        out.put("redisPasswordSet", has(env.getProperty("spring.data.redis.password")));
        out.put("pgPasswordSet", has(env.getProperty("pgvector.password")));
        out.put("minioSecretSet", has(env.getProperty("storage.minio.secret-key")));
        out.put("esApiKeySet", has(env.getProperty("search.es.api-key")));
        out.put("esPasswordSet", has(env.getProperty("search.es.password")));
        out.put("qdrantApiKeySet", has(env.getProperty("vector.qdrant.api-key")));
        return out;
    }

    /** 保存前预检；返回每个组件的连接结果，不写配置。 */
    public Map<String, Object> test(Map<String, Object> req) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mysql", testJdbc(value(req, "mysqlUrl"), value(req, "mysqlUsername"),
                secret(req, "mysqlPassword", "spring.datasource.password"), "MySQL"));
        result.put("pg", testJdbc(value(req, "pgUrl"), value(req, "pgUsername"),
                secret(req, "pgPassword", "pgvector.password"), "PostgreSQL"));
        result.put("redis", testTcp(value(req, "redisHost"), value(req, "redisPort")));
        result.put("minio", testHttp(value(req, "minioEndpoint") + "/minio/health/live", null, null));
        result.put("es", testHttp(value(req, "esUrl") + "/_cluster/health", null, null));
        result.put("qdrant", testHttp(value(req, "qdrantUrl") + "/collections", null, null));
        return result;
    }

    private Map<String, Object> testJdbc(String url, String username, String password, String label) {
        if (url == null || url.isBlank()) return Map.of("ok", false, "message", label + " URL 为空");
        try {
            DriverManager.setLoginTimeout(5);
            try (Connection ignored = DriverManager.getConnection(url, username, password)) {
                return Map.of("ok", true, "message", label + " 连接成功");
            }
        } catch (Exception e) {
            return Map.of("ok", false, "message", label + " 连接失败：" + root(e));
        }
    }

    private Map<String, Object> testTcp(String host, String port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host,
                    Integer.parseInt(port == null || port.isBlank() ? "6379" : port)), 3000);
            return Map.of("ok", true, "message", "Redis 端口可达");
        } catch (Exception e) {
            return Map.of("ok", false, "message", "Redis 连接失败：" + root(e));
        }
    }

    private Map<String, Object> testHttp(String url, String apiKey, String basic) {
        if (url == null || url.isBlank()) return Map.of("ok", false, "message", "URL 为空");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5)).GET();
            if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "ApiKey " + apiKey);
            HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = r.statusCode() >= 200 && r.statusCode() < 400;
            return Map.of("ok", ok, "message", "HTTP " + r.statusCode());
        } catch (Exception e) {
            return Map.of("ok", false, "message", "连接失败：" + root(e));
        }
    }

    private String root(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public synchronized Map<String, Object> save(Map<String, Object> req) {
        Map<String, Object> yaml = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("url", value(req, "mysqlUrl"));
        datasource.put("username", value(req, "mysqlUsername"));
        datasource.put("password", encryptedSecret(req, "mysqlPassword", "spring.datasource.password"));
        spring.put("datasource", datasource);
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", value(req, "redisHost"));
        redis.put("port", value(req, "redisPort"));
        redis.put("password", encryptedSecret(req, "redisPassword", "spring.data.redis.password"));
        spring.put("data", Map.of("redis", redis));
        yaml.put("spring", spring);

        Map<String, Object> pg = new LinkedHashMap<>();
        pg.put("url", value(req, "pgUrl"));
        pg.put("username", value(req, "pgUsername"));
        pg.put("password", encryptedSecret(req, "pgPassword", "pgvector.password"));
        yaml.put("pgvector", pg);

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", "minio");
        Map<String, Object> minio = new LinkedHashMap<>();
        minio.put("endpoint", value(req, "minioEndpoint"));
        minio.put("access-key", value(req, "minioAccessKey"));
        minio.put("secret-key", encryptedSecret(req, "minioSecret", "storage.minio.secret-key"));
        minio.put("bucket", value(req, "minioBucket"));
        storage.put("minio", minio);
        yaml.put("storage", storage);

        Map<String, Object> search = new LinkedHashMap<>();
        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("provider", "mysql");
        search.put("keyword", keyword);
        Map<String, Object> es = new LinkedHashMap<>();
        es.put("enabled", bool(req.get("esEnabled")));
        es.put("url", value(req, "esUrl"));
        es.put("index", value(req, "esIndex"));
        es.put("username", value(req, "esUsername"));
        es.put("password", encryptedSecret(req, "esPassword", "search.es.password"));
        es.put("api-key", encryptedSecret(req, "esApiKey", "search.es.api-key"));
        search.put("es", es);
        yaml.put("search", search);

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("store", "pgvector");
        Map<String, Object> qdrant = new LinkedHashMap<>();
        qdrant.put("enabled", bool(req.get("qdrantEnabled")));
        qdrant.put("url", value(req, "qdrantUrl"));
        qdrant.put("collection-prefix", value(req, "qdrantCollectionPrefix"));
        qdrant.put("api-key", encryptedSecret(req, "qdrantApiKey", "vector.qdrant.api-key"));
        vector.put("qdrant", qdrant);
        yaml.put("vector", vector);

        try {
            Path path = Path.of(configPath());
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            if (Files.isRegularFile(path)) {
                writeEncryptedBackup(path, backup);
            }
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Files.writeString(tmp, new Yaml(options).dump(yaml));
            try {
                Files.setPosixFilePermissions(tmp, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统继续使用默认权限
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("平台基础设施配置已保存：{}", path);
        } catch (IOException e) {
            throw new IllegalStateException("保存基础设施配置失败：" + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", true);
        out.put("restartRequired", true);
        out.put("configFile", configPath());
        return out;
    }

    private String value(Map<String, Object> req, String key) {
        Object v = req.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private String secret(Map<String, Object> req, String key, String currentProperty) {
        String value = value(req, key);
        return value.isBlank() ? env.getProperty(currentProperty, "") : value;
    }

    /** 落盘前加密；连接测试仍使用明文 secret()。空值保持为空。 */
    private String encryptedSecret(Map<String, Object> req, String key, String currentProperty) {
        String raw = secret(req, key, currentProperty);
        return raw == null || raw.isBlank() ? raw : cryptoService.encrypt(raw);
    }

    /**
     * 备份也写成加密快照。首次迁移时旧文件仍是明文，不能直接 copy 到 .bak。
     */
    private void writeEncryptedBackup(Path source, Path backup) {
        try {
            Object loaded = new Yaml().load(Files.readString(source));
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("配置根节点不是 Map");
            }
            encryptSecretValues(map);
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Files.writeString(backup, new Yaml(options).dump(map));
        } catch (Exception e) {
            // 备份解析失败时至少保留当前文件，权限仍收紧；不能因为备份问题阻断保存。
            log.warn("旧基础设施配置无法生成加密备份，保留原文件副本：{}", e.getMessage());
            try {
                Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyError) {
                log.warn("复制旧基础设施配置失败：{}", copyError.getMessage());
            }
        } finally {
            restrictPermissions(backup);
        }
    }

    private void encryptSecretValues(Object node) {
        if (!(node instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof String text && !text.isBlank()
                    && !text.startsWith("v1:") && isSecretKey(key)) {
                ((Map<Object, Object>) map).put(entry.getKey(), cryptoService.encrypt(text));
            } else {
                encryptSecretValues(value);
            }
        }
    }

    private boolean isSecretKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.endsWith("secret-key") || lower.endsWith("api-key");
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统继续使用默认权限
        }
    }

    private boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
