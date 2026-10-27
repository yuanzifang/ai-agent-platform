package com.platform.system.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
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

    public synchronized Map<String, Object> save(Map<String, Object> req) {
        Map<String, Object> yaml = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("url", value(req, "mysqlUrl"));
        datasource.put("username", value(req, "mysqlUsername"));
        datasource.put("password", secret(req, "mysqlPassword", "spring.datasource.password"));
        spring.put("datasource", datasource);
        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("host", value(req, "redisHost"));
        redis.put("port", value(req, "redisPort"));
        redis.put("password", secret(req, "redisPassword", "spring.data.redis.password"));
        spring.put("data", Map.of("redis", redis));
        yaml.put("spring", spring);

        Map<String, Object> pg = new LinkedHashMap<>();
        pg.put("url", value(req, "pgUrl"));
        pg.put("username", value(req, "pgUsername"));
        pg.put("password", secret(req, "pgPassword", "pgvector.password"));
        yaml.put("pgvector", pg);

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", "minio");
        Map<String, Object> minio = new LinkedHashMap<>();
        minio.put("endpoint", value(req, "minioEndpoint"));
        minio.put("access-key", value(req, "minioAccessKey"));
        minio.put("secret-key", secret(req, "minioSecret", "storage.minio.secret-key"));
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
        es.put("password", secret(req, "esPassword", "search.es.password"));
        es.put("api-key", secret(req, "esApiKey", "search.es.api-key"));
        search.put("es", es);
        yaml.put("search", search);

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("store", "pgvector");
        Map<String, Object> qdrant = new LinkedHashMap<>();
        qdrant.put("enabled", bool(req.get("qdrantEnabled")));
        qdrant.put("url", value(req, "qdrantUrl"));
        qdrant.put("collection-prefix", value(req, "qdrantCollectionPrefix"));
        qdrant.put("api-key", secret(req, "qdrantApiKey", "vector.qdrant.api-key"));
        vector.put("qdrant", qdrant);
        yaml.put("vector", vector);

        try {
            Path path = Path.of(configPath());
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
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

    private boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
