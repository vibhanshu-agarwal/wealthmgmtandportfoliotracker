package com.wealth.gateway.presence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DemoPresenceSourceGuardTest {

    private static final List<String> FORBIDDEN_PRESENCE_REFERENCES = List.of(
            "com.wealth.gateway.presence",
            "DemoPresenceService",
            "JwtSessionIdentity",
            "presence:demo",
            "/api/presence/demo",
            "anotherSessionActive");

    @Test
    void resetSourcesMustNotReferencePresenceModule() throws Exception {
        Path repoRoot = locateRepositoryRoot();

        for (String module : List.of("api-gateway", "portfolio-service")) {
            Path mainJava = repoRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(mainJava)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(mainJava)) {
                paths.filter(DemoPresenceSourceGuardTest::isResetSource)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> assertNoPresenceReferences(path));
            }
        }
    }

    private static Path locateRepositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle")) || Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
    }

    private static boolean isResetSource(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase();
        return normalized.contains("/reset/")
                || normalized.contains("demoreset")
                || normalized.contains("/demo_reset/");
    }

    private static void assertNoPresenceReferences(Path path) {
        try {
            String source = Files.readString(path);
            for (String forbidden : FORBIDDEN_PRESENCE_REFERENCES) {
                assertThat(source)
                        .as("reset source must not reference presence (%s) in %s", forbidden, path)
                        .doesNotContain(forbidden);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
