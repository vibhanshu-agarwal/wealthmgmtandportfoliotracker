package com.wealth.portfolio.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Property 1: Bug Condition — production slim JRE must contain {@code java.security.sasl}.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.4</b>
 *
 * <p>This is the bug-condition exploration test for the {@code price-update-stale-jackson3-regression}
 * spec (hypothesis H1). It encodes the <i>expected</i> (fixed) behavior: every Kafka-connected
 * service jlink fallback module list MUST include {@code java.security.sasl}, which the Aiven
 * SASL_SSL / {@code PLAIN} transport requires to create a {@link javax.security.sasl.SaslClient}.
 *
 * <p>Bug condition (from design):
 * <pre>
 * isBugCondition(input) = isWellFormed(event)
 *                         AND isProductionRuntime(prod profile, SASL_SSL, jlink-custom JRE)
 *                         AND notProjected
 * </pre>
 *
 * <p><b>On the UNFIXED code this test is EXPECTED TO FAIL</b>: the jlink fallback lists include
 * {@code java.security.jgss} but omit {@code java.security.sasl}, so the slim custom JRE cannot
 * initialize Kafka SASL authentication and the consumer never joins the group. When the fix is
 * implemented, this same test passes and acts as the regression guard (Task 3.2).
 *
 * <p>Counterexample documented when failing:
 * {@code produced slim JRE lacks java.security.sasl; Kafka SASL PLAIN client cannot be created
 * over SASL_SSL → consumer never joins the group → projection never runs}.
 */
class JlinkSaslModuleBugConditionTest {

    private static final String REQUIRED_SASL_MODULE = "java.security.sasl";

    /**
     * H1 direct counterexample: jlink fallback lists for Kafka-connected services must pin the
     * SASL client module explicitly.
     */
    @Test
    void kafkaConnectedDockerfiles_fallbackModuleSet_containsJavaSecuritySasl() throws Exception {
        Path repoRoot = JlinkDockerfileModuleSupport.repoRoot();
        var missing = new LinkedHashSet<String>();

        for (Path dockerfile : JlinkDockerfileModuleSupport.kafkaConnectedJlinkDockerfiles(repoRoot)) {
            Set<String> fallbackModules = JlinkDockerfileModuleSupport.parseFallbackModules(dockerfile);
            if (!fallbackModules.contains(REQUIRED_SASL_MODULE)) {
                missing.add(repoRoot.relativize(dockerfile).toString().replace('\\', '/'));
            }
        }

        assertThat(missing)
                .as(
                        "Kafka-connected jlink Dockerfiles must include %s in the --add-modules "
                                + "fallback list so SASL_SSL PLAIN transport can initialize "
                                + "(counterexample: %s)",
                        REQUIRED_SASL_MODULE,
                        "produced slim JRE lacks java.security.sasl; Kafka SASL PLAIN client cannot "
                                + "be created over SASL_SSL → consumer never joins the group → "
                                + "projection never runs")
                .isEmpty();
    }

    /**
     * H1 corroborating counterexample: simulate the unfixed fallback module set via
     * {@code --limit-modules} and verify a SASL PLAIN client can be created.
     */
    @Test
    void productionFallbackModuleSet_allowsSaslPlainClientCreation() throws Exception {
        Path repoRoot = JlinkDockerfileModuleSupport.repoRoot();
        Path referenceDockerfile = repoRoot.resolve("portfolio-service/Dockerfile");
        Set<String> fallbackModules = JlinkDockerfileModuleSupport.parseFallbackModules(referenceDockerfile);

        Set<String> limitedModules = new LinkedHashSet<>();
        limitedModules.add("java.base");
        limitedModules.addAll(fallbackModules);

        String moduleList =
                limitedModules.stream().sorted().collect(Collectors.joining(","));

        Path probeDir = repoRoot.resolve("portfolio-service/build/classes/java/test");
        assertThat(probeDir.resolve("com/wealth/portfolio/docker/SlimJreSaslClientProbe.class"))
                .as("Compile test sources before running the SASL module probe")
                .exists();

        ProcessBuilder builder =
                new ProcessBuilder(
                        ProcessHandle.current().info().command().orElse("java"),
                        "--limit-modules",
                        moduleList,
                        "-cp",
                        probeDir.toString(),
                        SlimJreSaslClientProbe.class.getName());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        assertThat(exitCode)
                .as(
                        "SASL PLAIN client probe over fallback module set [%s] (output: %s). "
                                + "Missing java.security.sasl prevents SASL_SSL consumer startup.",
                        moduleList,
                        output.trim())
                .isZero();
        assertThat(output).contains("SASL_CLIENT_OK");
    }
}
