package com.wealth.market;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forked-process integration test for {@link MarketDataRefreshJobRunner}.
 *
 * <p>Uses a real {@code System.exit} path in a child JVM so the Gradle test runner is not
 * terminated. Requires the {@code market-data.boot.jar} system property (wired from the
 * {@code integrationTest} Gradle task).
 */
@Tag("integration")
@Testcontainers
class MarketDataRefreshJobRunnerProcessIT {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Container
    static final ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        String responseBody = """
            {
              "quoteResponse": {
                "result": [
                  {"symbol": "AAPL", "regularMarketPrice": 150.0}
                ]
              }
            }
            """;

        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void forkedRunnerExitsZeroAfterSuccessfulRefresh() throws Exception {
        int exitCode = runForkedRunner(kafka.getBootstrapServers(), false, null);
        assertThat(exitCode).isZero();
    }

    @Test
    void forkedRunnerExitsOneWhenKafkaPublishFails() throws Exception {
        int exitCode = runForkedRunner(
                kafka.getBootstrapServers(),
                true,
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        assertThat(exitCode).isEqualTo(1);
    }

    private static int runForkedRunner(
            String kafkaBootstrapServers,
            boolean injectPublishFailure,
            String valueSerializerOverride) throws Exception {
        String bootJar = System.getProperty("market-data.boot.jar");
        assertThat(bootJar)
                .as("integrationTest task must set market-data.boot.jar to the bootJar output")
                .isNotBlank();
        assertThat(Files.exists(Path.of(bootJar))).isTrue();

        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Duser.timezone=Asia/Kolkata");
        command.add("-Dspring.main.web-application-type=none");
        command.add("-Dmarket-data.job-runner.enabled=true");
        command.add("-Dmarket-data.refresh.enabled=false");
        command.add("-Dmarket-data.seed.enabled=false");
        command.add("-Dmarket-data.baseline-seed.enabled=false");
        command.add("-Dmarket.seed.enabled=false");
        command.add("-Dmanagement.tracing.export.enabled=false");
        command.add("-Dmanagement.otlp.metrics.export.enabled=false");
        command.add("-Dspring.mongodb.uri=" + mongo.getReplicaSetUrl());
        command.add("-Dspring.kafka.bootstrap-servers=" + kafkaBootstrapServers);
        if (valueSerializerOverride != null) {
            command.add("-Dspring.kafka.producer.value-serializer=" + valueSerializerOverride);
        }
        command.add("-Dexternal-market-data.base-url=http://127.0.0.1:" + wireMockServer.port());
        command.add("-Dmarket.baseline.tickers[0]=AAPL");
        command.add("-jar");
        command.add(bootJar);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread outputDrain = new Thread(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (Exception ignored) {
                // Process may close the stream while shutting down.
            }
        }, "market-data-runner-output-drain");
        outputDrain.setDaemon(true);
        outputDrain.start();

        long timeoutSeconds = injectPublishFailure ? 90 : 180;
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        outputDrain.join(5_000);

        String processOutput = output.toString();
        assertThat(finished)
                .as("forked runner did not exit within %ds; output so far:\n%s", timeoutSeconds, processOutput)
                .isTrue();
        int exitCode = process.exitValue();

        assertThat(exitCode)
                .as("forked runner output:\n%s", processOutput)
                .isIn(0, 1);
        return exitCode;
    }
}
