package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Configuration and architecture smoke tests for the production-rate-limiting feature
 * (design.md "Configuration / architecture (smoke) tests"; tasks.md 7.3).
 *
 * <p>These are plain unit tests (no Spring context, no Testcontainers) that read the raw YAML
 * resources and class bytecode directly, so they run fast under {@code ./gradlew test}.
 */
class RateLimitConfigurationGuardrailTest {

    // Read directly from src/main/resources (not the test classpath) so these assertions target
    // the production YAML content specifically, unaffected by test-only resource overrides in
    // src/test/resources that shadow the same filenames on the test classpath.
    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

    // -------------------------------------------------------------------------
    // Req 1.2, 2.4 — application.yml stays free of Redis / RequestRateLimiter config
    // -------------------------------------------------------------------------

    @Test
    void applicationYmlHasNoRedisConfiguration() throws IOException {
        List<String> codeLines = readNonCommentLines("application.yml");

        assertThat(codeLines).noneMatch(line -> line.toLowerCase().contains("spring.data.redis"));
        assertThat(codeLines).noneMatch(line -> line.trim().equals("redis:"));
    }

    @Test
    void applicationYmlHasNoRequestRateLimiterReference() throws IOException {
        List<String> codeLines = readNonCommentLines("application.yml");

        assertThat(codeLines).noneMatch(line -> line.contains("RequestRateLimiter"));
    }

    @Test
    void applicationYmlHasNoRateLimitProperties() throws IOException {
        List<String> codeLines = readNonCommentLines("application.yml");

        assertThat(codeLines).noneMatch(line -> line.contains("rate-limit"));
    }

    // -------------------------------------------------------------------------
    // Req 8.3 — application-aws.yml never redefines app.rate-limit.* (inherits from prod)
    // -------------------------------------------------------------------------

    @Test
    void applicationAwsYmlHasNoRateLimitValues() throws IOException {
        List<String> codeLines = readNonCommentLines("application-aws.yml");

        assertThat(codeLines).noneMatch(line -> line.contains("rate-limit"));
    }

    // -------------------------------------------------------------------------
    // Req 2.3 — management.health.redis.enabled resolves to false under azure/aws.
    //
    // This is asserted at the full Spring-context level (a real environment resolution,
    // exercising Boot's YAML binding) by GatewayBootContractTest / InfrastructureHealthLoggerProfileTest,
    // which boot the context under each profile. Here we additionally assert the raw YAML content
    // as a fast, context-free smoke check that the setting is present verbatim in each profile file.
    // -------------------------------------------------------------------------

    @Test
    void azureYmlDisablesRedisHealthIndicator() throws IOException {
        String yaml = readMainResource("application-azure.yml");

        assertThat(yaml).contains("redis:").contains("enabled: false");
    }

    @Test
    void awsYmlDisablesRedisHealthIndicator() throws IOException {
        String yaml = readMainResource("application-aws.yml");

        assertThat(yaml).contains("redis:").contains("enabled: false");
    }

    // -------------------------------------------------------------------------
    // Req 5.5 — each route's retry-after-seconds metadata must match
    // ceil(requestedTokens / replenishRate) for the limiter actually attached to that route.
    // This drifts silently if either side is edited without the other (flagged in the YAML
    // comments); this test turns that drift into a build failure instead of a runtime bug.
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void retryAfterSecondsMatchesLimiterMathForEveryRoute() throws IOException {
        Map<String, Object> yaml;
        try (InputStream in = Files.newInputStream(MAIN_RESOURCES.resolve("application-prod.yml"))) {
            yaml = new Yaml().load(in);
        }

        Map<String, Object> app = (Map<String, Object>) yaml.get("app");
        Map<String, Object> rateLimit = (Map<String, Object>) app.get("rate-limit");
        Map<String, Integer> expectedRetryAfterByLimiterBean = new HashMap<>();
        for (String limiterName : List.of("standard", "strict")) {
            Map<String, Object> config = (Map<String, Object>) rateLimit.get(limiterName);
            int replenishRate = (int) config.get("replenish-rate");
            int requestedTokens = (int) config.get("requested-tokens");
            int expected = (int) Math.ceil((double) requestedTokens / replenishRate);
            expectedRetryAfterByLimiterBean.put(limiterName + "RateLimiter", expected);
        }

        Map<String, Object> spring = (Map<String, Object>) yaml.get("spring");
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
        Map<String, Object> server = (Map<String, Object>) gateway.get("server");
        Map<String, Object> webflux = (Map<String, Object>) server.get("webflux");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) webflux.get("routes");

        List<String> mismatches = new java.util.ArrayList<>();
        for (Map<String, Object> route : routes) {
            List<Map<String, Object>> filters = (List<Map<String, Object>>) route.get("filters");
            if (filters == null || filters.isEmpty()) {
                continue; // exempt route (e.g. /api/internal/**) — no RequestRateLimiter, nothing to check
            }
            Map<String, Object> filterArgs = (Map<String, Object>) filters.get(0).get("args");
            String rateLimiterRef = (String) filterArgs.get("rate-limiter");
            String limiterBeanName = rateLimiterRef.replaceAll("[#{}@]", "");

            Map<String, Object> metadata = (Map<String, Object>) route.get("metadata");
            int actualRetryAfter = (int) metadata.get("retry-after-seconds");
            int expectedRetryAfter = expectedRetryAfterByLimiterBean.get(limiterBeanName);

            if (actualRetryAfter != expectedRetryAfter) {
                mismatches.add(String.format(
                        "route '%s' uses %s (expects retry-after-seconds=%d) but declares %d",
                        route.get("id"), limiterBeanName, expectedRetryAfter, actualRetryAfter));
            }
        }

        assertThat(mismatches).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Req 2.2 — no software.amazon.awssdk.* / io.lettuce.* imports in com.wealth.gateway,
    // beyond the pre-existing RedisSslConfig (out of scope for this feature; see design.md).
    // -------------------------------------------------------------------------

    private static final JavaClasses GATEWAY_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.wealth.gateway");

    @Test
    void noNewClassesImportAwsSdkOrLettuce() {
        noClasses()
                .that().resideInAPackage("com.wealth.gateway")
                .and().haveSimpleNameNotEndingWith("RedisSslConfig")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("software.amazon.awssdk..", "io.lettuce..")
                .check(GATEWAY_CLASSES);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String readMainResource(String name) throws IOException {
        Path path = MAIN_RESOURCES.resolve(name);
        assertThat(Files.exists(path)).as("file " + path).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Reads a YAML resource and strips full-line comments (lines whose trimmed form starts with '#'). */
    private static List<String> readNonCommentLines(String name) throws IOException {
        Path path = MAIN_RESOURCES.resolve(name);
        assertThat(Files.exists(path)).as("file " + path).isTrue();
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.trim().startsWith("#"))
                .collect(Collectors.toList());
    }
}
