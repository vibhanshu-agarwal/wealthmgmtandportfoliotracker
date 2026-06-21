package com.wealth.market.docker;

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
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import com.wealth.market.TestContainerImages;
import org.testcontainers.utility.MountableFile;

/** Property 7: slim-JRE image boots with MongoDB and reports {@code /actuator/health = UP}. */
@Tag("slim-image")
@Testcontainers
class SlimImageHealthIT {

    private static final Path REPO_ROOT = Path.of(System.getProperty("repo.root"));
    private static final DockerImageName SLIM_IMAGE =
            DockerImageName.parse(System.getProperty("slim.test.image"));
    private static final int APP_PORT = 8082;
    private static final Network NETWORK = Network.newNetwork();

    @Container
    @SuppressWarnings("resource")
    static final MongoDBContainer mongo =
            new MongoDBContainer(TestContainerImages.MONGO).withNetwork(NETWORK).withNetworkAliases("mongo");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> marketData =
            new GenericContainer<>(SLIM_IMAGE)
                    .withNetwork(NETWORK)
                    .withExposedPorts(APP_PORT)
                    .withEnv("SPRING_PROFILES_ACTIVE", "aws")
                    .withEnv("SPRING_MONGODB_URI", "mongodb://mongo:27017/market_db")
                    .withEnv("MANAGEMENT_TRACING_EXPORT_ENABLED", "false")
                    .withEnv("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "false")
                    .dependsOn(mongo)
                    .withStartupTimeout(Duration.ofMinutes(8))
                    .waitingFor(
                            Wait.forHttp("/actuator/health")
                                    .forPort(APP_PORT)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(8)));

    @Test
    void slimImage_actuatorHealthIsUp() throws Exception {
        int port = marketData.getMappedPort(APP_PORT);
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
        marketData.copyFileToContainer(
                MountableFile.forHostPath(
                        REPO_ROOT.resolve(
                                "market-data-service/build/classes/java/test/com/wealth/market/docker/SlimJreTlsProbe.class")),
                "/probe/com/wealth/market/docker/SlimJreTlsProbe.class");
        var result =
                marketData.execInContainer(
                        "/opt/java/bin/java", "-cp", "/probe", "com.wealth.market.docker.SlimJreTlsProbe");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("TLS_OK");
    }
}
