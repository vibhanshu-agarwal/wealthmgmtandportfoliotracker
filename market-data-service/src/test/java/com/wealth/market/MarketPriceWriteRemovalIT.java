package com.wealth.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.wealth.market.events.PriceUpdatedEvent;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Security regression for the removed public price write ({@code POST /api/market/prices/{ticker}}).
 *
 * <p>The gateway lets any ordinary signed-in account ({@code ro=false}) reach {@code /api/market/**}
 * and forwards it with a gateway-set {@code X-User-Id}; only this service can refuse the write. So
 * the proof is the shared data itself: every mutating request, carrying each caller's forwarded
 * identity, must leave the MongoDB price documents byte-for-byte unchanged and put nothing on the
 * {@code market-prices} topic. A status code alone would not show that.
 *
 * <p>The control write through {@link MarketPriceService} (the path local seeding uses) proves the
 * harness can see a real mutation in both stores, so the "unchanged" assertions cannot pass
 * vacuously.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",
        "spring.kafka.producer.properties.spring.json.add.type.headers=true",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class MarketPriceWriteRemovalIT {

    private static final String TOPIC = "market-prices";
    private static final String COLLECTION = "market_prices";

    /** Identity headers as the gateway forwards them for each caller class. */
    private static final Map<String, Map<String, String>> CALLERS = Map.of(
            "no forwarded identity", Map.of(),
            "ordinary signed-up account", Map.of("X-User-Id", UUID.randomUUID().toString()),
            "showcase account", Map.of("X-User-Id", "00000000-0000-0000-0000-0000000d3110"));

    private static final List<String> METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    /** The removed route, the collection path, and a ticker with no stored document yet. */
    private static final List<String> PATHS = List.of(
            "/api/market/prices/AAPL", "/api/market/prices", "/api/market/prices/NEWTICK");

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer(TestContainerImages.MONGO);

    @Container
    static final ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(TestContainerImages.KAFKA);

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("market-data.refresh.enabled", () -> false);
        registry.add("market-data.seed.enabled", () -> false);
        registry.add("market-data.baseline-seed.enabled", () -> false);
        registry.add("market.seed.enabled", () -> false);
    }

    @LocalServerPort int port;

    @Autowired AssetPriceRepository assetPriceRepository;
    @Autowired MarketPriceService marketPriceService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void mutatingRequestsFromEveryCallerLeaveSharedPricesAndTopicUnchanged() throws Exception {
        AssetPrice aapl = new AssetPrice("AAPL", new BigDecimal("195.00"));
        aapl.setQuoteCurrency("USD");
        assetPriceRepository.save(aapl);
        List<Document> before = snapshotPrices();

        List<String> accepted = new ArrayList<>();
        for (var caller : CALLERS.entrySet()) {
            for (String method : METHODS) {
                for (String path : PATHS) {
                    int status = send(method, path, caller.getValue());
                    if (status < 400) {
                        accepted.add(caller.getKey() + " " + method + " " + path + " -> " + status);
                    }
                }
            }
        }

        // Control write through the service path local seeding uses: must be visible in both stores.
        marketPriceService.updatePrice("CTRL", new BigDecimal("42.00"), "USD");
        List<String> publishedKeys = publishedKeysAfterFlush();

        List<Document> after = snapshotPrices();

        // Prerequisites: without these the "unchanged" checks below would be vacuous.
        assertThat(publishedKeys).as("the control write must reach the topic").contains("CTRL");
        assertThat(after.stream().map(d -> d.getString("_id")).toList())
                .as("the control write must reach Mongo")
                .contains("CTRL");

        // Soft, so a regression reports every effect at once: status, topic and stored data.
        assertSoftly(softly -> {
            softly.assertThat(accepted).as("mutating requests that were not refused").isEmpty();
            softly.assertThat(publishedKeys)
                    .as("events on the topic besides the control write")
                    .containsExactly("CTRL");
            softly.assertThat(after.stream().filter(d -> !"CTRL".equals(d.getString("_id"))).toList())
                    .as("shared price documents, byte-for-byte")
                    .isEqualTo(before);
        });

        // Reads still serve the stored price.
        HttpResponse<String> read = http.send(
                HttpRequest.newBuilder(uri("/api/market/prices?tickers=AAPL")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("\"ticker\":\"AAPL\"").contains("195");
    }

    @Test
    void noHandlerUnderApiMarketAcceptsAMutatingMethod() {
        Map<RequestMappingInfo, ?> marketMappings = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/market")))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(marketMappings).as("the public market read routes must still be mapped").isNotEmpty();

        for (RequestMappingInfo info : marketMappings.keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            assertThat(methods)
                    .as("%s must declare its methods (an empty set accepts every method)", info)
                    .isNotEmpty();
            assertThat(methods)
                    .as("%s must be read-only", info)
                    .isSubsetOf(RequestMethod.GET, RequestMethod.HEAD);
        }
    }

    private int send(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.BodyPublisher body = "DELETE".equals(method)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("1.00");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body);
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Every stored price document, raw, in a stable order. */
    private List<Document> snapshotPrices() {
        return mongoTemplate.findAll(Document.class, COLLECTION).stream()
                .sorted(Comparator.comparing(d -> d.getString("_id")))
                .toList();
    }

    /**
     * Flushes the application's own producer, then reads every partition of the topic to its end
     * offset. Anything the application sent before the flush is therefore read, independent of
     * partition count or timing.
     */
    private List<String> publishedKeysAfterFlush() {
        kafkaTemplate.flush();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "price-write-removal-it-" + UUID.randomUUID(),
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(),
                new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC, Duration.ofSeconds(30)).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            List<String> keys = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("did not reach the topic end offsets " + end);
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    keys.add(record.key());
                }
            }
            return keys;
        }
    }
}
