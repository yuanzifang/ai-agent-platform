package com.platform.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止并发开发再次提交重复 Flyway 版本号，导致应用启动直接失败。 */
class MigrationVersionTest {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void migrationVersionsAreUnique() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        assertTrue(Files.isDirectory(dir), "migration directory missing: " + dir.toAbsolutePath());
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                Matcher matcher = VERSION.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    byVersion.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
                            .add(file.getFileName().toString());
                }
            });
        }
        List<String> duplicates = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "V" + entry.getKey() + " => " + entry.getValue())
                .toList();
        assertTrue(duplicates.isEmpty(), "重复 Flyway 版本（会导致启动失败）：" + duplicates);
    }
}
