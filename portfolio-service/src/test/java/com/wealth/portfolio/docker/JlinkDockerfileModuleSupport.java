package com.wealth.portfolio.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses jlink {@code --add-modules} fallback module lists from service Dockerfiles.
 *
 * <p>Only Dockerfiles that contain a jlink stage are considered. Azure variants use a full
 * OpenJDK runtime and are intentionally excluded.
 */
final class JlinkDockerfileModuleSupport {

    private static final Pattern ADD_MODULES =
            Pattern.compile("--add-modules\\s+\"\\$\\{DEPS\\},([^\"]+)\"");

    /** Kafka-connected services whose production runtime opens a SASL_SSL broker connection. */
    static final List<String> KAFKA_CONNECTED_SERVICES =
            List.of("portfolio-service", "insight-service", "market-data-service");

    static final List<String> JLINK_DOCKERFILE_VARIANTS = List.of("Dockerfile", "Dockerfile.slim-it");

    private JlinkDockerfileModuleSupport() {}

    static Path repoRoot() {
        String configured = System.getProperty("repo.root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("settings.gradle"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("settings.gradle"))) {
            return parent;
        }
        throw new IllegalStateException(
                "Cannot locate repository root; set -Drepo.root to the wealthmgmtandportfoliotracker directory");
    }

    static Set<String> parseFallbackModules(Path dockerfile) throws IOException {
        String content = Files.readString(dockerfile);
        Matcher matcher = ADD_MODULES.matcher(content);
        if (!matcher.find()) {
            throw new IllegalArgumentException("No jlink --add-modules line found in " + dockerfile);
        }
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .filter(module -> !module.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static List<Path> kafkaConnectedJlinkDockerfiles(Path repoRoot) {
        return KAFKA_CONNECTED_SERVICES.stream()
                .flatMap(
                        service ->
                                JLINK_DOCKERFILE_VARIANTS.stream()
                                        .map(variant -> repoRoot.resolve(service).resolve(variant)))
                .toList();
    }
}
