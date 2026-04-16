package com.wealth.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class LocalMarketDataSeederIntegrationTest {

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

  @DynamicPropertySource
  static void integrationTestProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    registry.add("market-data.refresh.enabled", () -> false);
    registry.add("market-data.hydration.enabled", () -> false);
    registry.add("market-data.baseline-seed.enabled", () -> false);
  }

  @MockitoBean
  @SuppressWarnings("unused")
  KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

  @Autowired AssetPriceRepository assetPriceRepository;

  @Autowired(required = false)
  LocalMarketDataSeeder seeder;

  @Autowired ApplicationContext applicationContext;

  @Autowired(required = false)
  MarketDataRefreshJob marketDataRefreshJob;

  // -------------------------------------------------------------------------
  // 7.1 — context loads and seeds all 6 fixture assets
  // -------------------------------------------------------------------------
  @Test
  void contextLoads_andSeedsFixture() {
    assertThat(marketDataRefreshJob).as("scheduled refresh off under test classpath application.yml")
        .isNull();

    var tickers = assetPriceRepository.findAll().stream().map(AssetPrice::getTicker).toList();

    assertThat(tickers).containsExactlyInAnyOrder("AAPL", "TSLA", "BTC", "MSFT", "NVDA", "ETH");
  }

  // -------------------------------------------------------------------------
  // 7.2 — seeder is idempotent: running twice does not duplicate documents
  // -------------------------------------------------------------------------
  @Test
  void seeder_isIdempotent() {
    long countBefore = assetPriceRepository.count();

    // Run the seeder a second time manually
    seeder.run(null);

    long countAfter = assetPriceRepository.count();
    assertThat(countAfter).isEqualTo(countBefore);
  }
}
