package com.wealth.gateway.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Property 7: slim-JRE image boots and serves {@code /actuator/health = UP} with outbound TLS.
 *
 * <p>Image is built by Gradle {@code buildSlimTestImage} from {@code Dockerfile.slim-it}
 * (host bootJar + jlink; no in-container Gradle).
 */
@Tag("slim-image")
@Testcontainers
class SlimImageHealthIT {

    private static final Path REPO_ROOT = Path.of(System.getProperty("repo.root"));
    private static final DockerImageName SLIM_IMAGE =
            DockerImageName.parse(System.getProperty("slim.test.image"));
    private static final int APP_PORT = 8080;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> gateway =
            new GenericContainer<>(SLIM_IMAGE)
                    .withExposedPorts(APP_PORT)
                    .withEnv("SPRING_PROFILES_ACTIVE", "aws")
                    .withEnv("AUTH_JWT_SECRET", "slim-image-test-secret-min-32-characters")
                    .withEnv("MANAGEMENT_TRACING_EXPORT_ENABLED", "false")
                    .withEnv("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "false")
                    .withStartupTimeout(Duration.ofMinutes(8))
                    .waitingFor(
                            Wait.forHttp("/actuator/health")
                                    .forPort(APP_PORT)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(8)));

    @Test
    void slimImage_actuatorHealthIsUp() throws Exception {
        int port = gateway.getMappedPort(APP_PORT);
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder()
                                        .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void slimJre_completesOutboundTls() throws Exception {
        gateway.copyFileToContainer(
                MountableFile.forHostPath(
                        REPO_ROOT.resolve(
                                "api-gateway/build/classes/java/test/com/wealth/gateway/docker/SlimJreTlsProbe.class")),
                "/probe/com/wealth/gateway/docker/SlimJreTlsProbe.class");
        var result =
                gateway.execInContainer(
                        "/opt/java/bin/java", "-cp", "/probe", "com.wealth.gateway.docker.SlimJreTlsProbe");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("TLS_OK");
    }
}
