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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaTokenToolTest {

    private static Path replicaTokenJar;

    @BeforeAll
    static void locateReplicaTokenJar() {
        replicaTokenJar = Path.of("build/libs/replica-token.jar").toAbsolutePath().normalize();
        assertThat(replicaTokenJar)
                .as("replica-token.jar must exist; run :api-gateway:replicaTokenJar before this test class")
                .exists();
    }

    @Test
    void successVectorEmitsExactStdoutAndEmptyStderr() throws Exception {
        ToolRun run = runTool("api-gateway--0000000-abcdefg");

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).isEqualTo("95ca17821ade\n");
        assertThat(run.stderr()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidInvocationCases")
    void invalidInvocationsExitNonZero(String[] args) throws Exception {
        ToolRun run = runTool(args);

        assertThat(run.exitCode()).isNotZero();
    }

    private static Stream<Arguments> invalidInvocationCases() {
        return Stream.of(
                Arguments.of((Object) new String[] {}),
                Arguments.of((Object) new String[] {""}),
                Arguments.of((Object) new String[] {"   \t\n"}),
                Arguments.of((Object) new String[] {"api-gateway--0000000-abcdefg", "extra"}));
    }

    private static ToolRun runTool(String... args) throws Exception {
        String javaBin =
                Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(replicaTokenJar.toString());
        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Thread stdoutDrain = drain(process.getInputStream(), stdout);
        Thread stderrDrain = drain(process.getErrorStream(), stderr);

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        stdoutDrain.join(5_000);
        stderrDrain.join(5_000);

        assertThat(finished).isTrue();
        return new ToolRun(
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
                "replica-token-output-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record ToolRun(int exitCode, String stdout, String stderr) {}
}
