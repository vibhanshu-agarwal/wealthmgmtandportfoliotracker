package com.wealth.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyPresenceProbeTest {

    private static Path probeJar;

    @BeforeAll
    static void locateProbeJar() {
        probeJar = Path.of("build/libs/probe.jar").toAbsolutePath().normalize();
        assertThat(probeJar)
                .as("probe.jar must exist; run :api-gateway:probeJar before this test class")
                .exists();
    }

    @ParameterizedTest
    @MethodSource("classificationCases")
    void classifyMatchesStringIsBlank(String value, String expected) {
        assertThat(InternalApiKeyPresenceProbe.classify(value)).isEqualTo(expected);
    }

    private static Stream<Arguments> classificationCases() {
        return Stream.of(
                Arguments.of(null, "blank"),
                Arguments.of("", "blank"),
                Arguments.of("   \t\n", "blank"),
                Arguments.of("\u2003", "blank"),
                Arguments.of("smoke-test-value", "nonblank"));
    }

    @ParameterizedTest
    @MethodSource("processCases")
    void mainInChildJvmEmitsExactStdoutAndEmptyStderr(String envValue, String expectedStdout)
            throws Exception {
        ProbeRun run = runProbe(envValue);

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).isEqualTo(expectedStdout);
        assertThat(run.stderr()).isEmpty();
    }

    private static Stream<Arguments> processCases() {
        return Stream.of(
                Arguments.of(null, "blank\n"),
                Arguments.of("", "blank\n"),
                Arguments.of("   \t\n", "blank\n"),
                Arguments.of("\u2003", "blank\n"),
                Arguments.of("smoke-test-value", "nonblank\n"));
    }

    private static ProbeRun runProbe(String envValue) throws Exception {
        String javaBin =
                Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(probeJar.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.remove("INTERNAL_API_KEY");
        if (envValue != null) {
            environment.put("INTERNAL_API_KEY", envValue);
        }

        Process process = builder.start();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Thread stdoutDrain = drain(process.getInputStream(), stdout);
        Thread stderrDrain = drain(process.getErrorStream(), stderr);

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        stdoutDrain.join(5_000);
        stderrDrain.join(5_000);

        assertThat(finished).isTrue();
        return new ProbeRun(
                process.exitValue(),
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream target) {
        Thread thread = new Thread(
                () -> {
                    try {
                        stream.transferTo(target);
                    } catch (Exception ignored) {
                        // Process may close the stream while shutting down.
                    }
                },
                "probe-output-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    void mainUsesPrintNotPrintln() {
        assertThat(InternalApiKeyPresenceProbe.classify("x")).isEqualTo("nonblank");
    }

    private record ProbeRun(int exitCode, String stdout, String stderr) {}
}
