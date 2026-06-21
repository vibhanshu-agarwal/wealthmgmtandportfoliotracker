package com.wealth.portfolio.docker;

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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.wealth.portfolio.TestContainerImages;
import org.testcontainers.utility.MountableFile;

/** Property 7: slim-JRE image boots with Postgres and reports {@code /actuator/health = UP}. */
@Tag("slim-image")
@Testcontainers
class SlimImageHealthIT {

    private static final Path REPO_ROOT = Path.of(System.getProperty("repo.root"));
    private static final DockerImageName SLIM_IMAGE =
            DockerImageName.parse(System.getProperty("slim.test.image"));
    private static final int APP_PORT = 8081;
    private static final Network NETWORK = Network.newNetwork();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres")
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis")
                    .withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> portfolio =
            new GenericContainer<>(SLIM_IMAGE)
                    .withNetwork(NETWORK)
                    .withExposedPorts(APP_PORT)
                    .withEnv("SPRING_PROFILES_ACTIVE", "aws")
                    .withEnv(
                            "SPRING_DATASOURCE_URL",
                            "jdbc:postgresql://postgres:5432/portfolio_db?options=-c%20timezone=Asia/Kolkata")
                    .withEnv("SPRING_DATASOURCE_USERNAME", "wealth_user")
                    .withEnv("SPRING_DATASOURCE_PASSWORD", "wealth_pass")
                    .withEnv("SPRING_DATA_REDIS_URL", "redis://redis:6379")
                    .withEnv("SPRING_KAFKA_LISTENER_AUTO_STARTUP", "false")
                    .withEnv("MANAGEMENT_HEALTH_KAFKA_ENABLED", "false")
                    .withEnv("MANAGEMENT_TRACING_EXPORT_ENABLED", "false")
                    .withEnv("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "false")
                    .dependsOn(postgres, redis)
                    .withStartupTimeout(Duration.ofMinutes(8))
                    .waitingFor(
                            Wait.forHttp("/actuator/health")
                                    .forPort(APP_PORT)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(8)));

    @Test
    void slimImage_actuatorHealthIsUp() throws Exception {
        int port = portfolio.getMappedPort(APP_PORT);
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
        portfolio.copyFileToContainer(
                MountableFile.forHostPath(
                        REPO_ROOT.resolve(
                                "portfolio-service/build/classes/java/test/com/wealth/portfolio/docker/SlimJreTlsProbe.class")),
                "/probe/com/wealth/portfolio/docker/SlimJreTlsProbe.class");
        var result =
                portfolio.execInContainer(
                        "/opt/java/bin/java", "-cp", "/probe", "com.wealth.portfolio.docker.SlimJreTlsProbe");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("TLS_OK");
    }
}
