package com.wealth.insight.docker;

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
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.wealth.insight.TestContainerImages;
import org.testcontainers.utility.MountableFile;

/** Property 7: slim-JRE image boots with Redis and reports {@code /actuator/health = UP}. */
@Tag("slim-image")
@Testcontainers
class SlimImageHealthIT {

    private static final Path REPO_ROOT = Path.of(System.getProperty("repo.root"));
    private static final DockerImageName SLIM_IMAGE =
            DockerImageName.parse(System.getProperty("slim.test.image"));
    private static final int APP_PORT = 8080;
    private static final Network NETWORK = Network.newNetwork();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis")
                    .withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> insight =
            new GenericContainer<>(SLIM_IMAGE)
                    .withNetwork(NETWORK)
                    .withExposedPorts(APP_PORT)
                    .withEnv("SPRING_PROFILES_ACTIVE", "aws")
                    .withEnv("SERVER_PORT", String.valueOf(APP_PORT))
                    .withEnv("REDIS_URL", "redis://redis:6379")
                    .withEnv("SPRING_KAFKA_LISTENER_AUTO_STARTUP", "false")
                    .withEnv("SPRING_AI_MODEL_CHAT", "none")
                    .withEnv("OPENAI_API_KEY", "placeholder-key")
                    .withEnv("AZURE_OPENAI_ENDPOINT", "https://placeholder.openai.azure.com/")
                    .withEnv("MANAGEMENT_TRACING_EXPORT_ENABLED", "false")
                    .withEnv("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "false")
                    .dependsOn(redis)
                    .withStartupTimeout(Duration.ofMinutes(8))
                    .waitingFor(
                            Wait.forHttp("/actuator/health")
                                    .forPort(APP_PORT)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(8)));

    @Test
    void slimImage_actuatorHealthIsUp() throws Exception {
        int port = insight.getMappedPort(APP_PORT);
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
        insight.copyFileToContainer(
                MountableFile.forHostPath(
                        REPO_ROOT.resolve(
                                "insight-service/build/classes/java/test/com/wealth/insight/docker/SlimJreTlsProbe.class")),
                "/probe/com/wealth/insight/docker/SlimJreTlsProbe.class");
        var result =
                insight.execInContainer(
                        "/opt/java/bin/java", "-cp", "/probe", "com.wealth.insight.docker.SlimJreTlsProbe");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("TLS_OK");
    }
}
