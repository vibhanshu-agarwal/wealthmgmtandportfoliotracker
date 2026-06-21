package com.wealth.market.seed;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.mongodb.MongoDBContainer;
import com.wealth.market.TestContainerImages;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "market-data.seed.enabled=false",
        "app.internal.api-key=test-internal-key"
})
class MarketDataSeedEndpointGatingIT {

    private static final MongoDBContainer MONGO = new MongoDBContainer(TestContainerImages.MONGO);

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("market-data.refresh.enabled", () -> false);
        registry.add("market-data.baseline-seed.enabled", () -> false);
        registry.add("market.seed.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:0");
    }

    @MockitoBean
    @SuppressWarnings("unused")
    KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    @LocalServerPort int port;

    @Test
    void seedEndpointReturns404WhenSeedDisabled() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/internal/market-data/seed"))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-Internal-Api-Key", "test-internal-key")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"userId\":\"00000000-0000-0000-0000-000000000e2e\"}"))
                .build();

        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
