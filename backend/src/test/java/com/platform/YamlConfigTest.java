package com.platform;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 主资源 YAML 配置守卫：snakeyaml 2.x 拒绝重复键，application.yml 一旦出现重复顶层键
 * （如两个 search:），Spring Boot 启动即崩，单元测试全绿但 E2E 后端起不来。
 * 这里按 Spring Boot 同款语义逐文件解析，把这类回归挡在 CI 后端测试阶段。
 */
class YamlConfigTest {

    @Test
    void 主资源YAML不允许重复键() throws IOException {
        Path resources = Paths.get("src", "main", "resources");
        try (Stream<Path> paths = Files.walk(resources)) {
            List<Path> yamls = paths.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".yml") || n.endsWith(".yaml");
            }).toList();
            assertFalse(yamls.isEmpty(), "未在 src/main/resources 找到 YAML 配置文件");
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            for (Path yamlFile : yamls) {
                try (InputStream in = Files.newInputStream(yamlFile)) {
                    // loadAll 惰性解析，必须迭代才触发重复键校验
                    new Yaml(new SafeConstructor(options)).loadAll(in).forEach(o -> { });
                } catch (Exception e) {
                    fail(yamlFile + " 解析失败（重复键会令 Spring Boot 无法启动）: " + e.getMessage());
                }
            }
        }
    }
}
