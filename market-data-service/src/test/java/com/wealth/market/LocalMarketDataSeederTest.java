package com.wealth.market;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

@ExtendWith(MockitoExtension.class)
class LocalMarketDataSeederTest {

  private static final String TEST_FIXTURE = "classpath:fixtures/test-market-seed-data.json";
  private static final String MISSING_FIXTURE = "classpath:fixtures/does-not-exist.json";

  @Mock private AssetPriceRepository assetPriceRepository;

  @Mock private MarketPriceService marketPriceService;

  private ObjectMapper objectMapper;
  private ResourceLoader resourceLoader;

  @BeforeEach
  void setUp() {
    objectMapper =
        new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .findAndRegisterModules();
    resourceLoader = new DefaultResourceLoader();
  }

  private LocalMarketDataSeeder seeder(boolean enabled, String fixturePath) {
    var props = new MarketSeedProperties(enabled, fixturePath);
    var s =
        new LocalMarketDataSeeder(assetPriceRepository, marketPriceService, objectMapper, props);
    s.setResourceLoader(resourceLoader);
    return s;
  }

  // -------------------------------------------------------------------------
  // 6.1 — seeds all assets when database is empty
  // -------------------------------------------------------------------------
  @Test
  void run_seedsAllAssets_whenDatabaseIsEmpty() {
    when(assetPriceRepository.findAll()).thenReturn(List.of());

    seeder(true, TEST_FIXTURE).run(null);

    verify(marketPriceService).updatePrice(eq("TEST1"), any(BigDecimal.class));
    verify(marketPriceService).updatePrice(eq("TEST2"), any(BigDecimal.class));
    verifyNoMoreInteractions(marketPriceService);
  }

  // -------------------------------------------------------------------------
  // 6.2 — skips all tickers when both are already present
  // -------------------------------------------------------------------------
  @Test
  void run_skipsExistingTickers_whenAlreadyPresent() {
    var test1 = new AssetPrice("TEST1", new BigDecimal("100.0"));
    var test2 = new AssetPrice("TEST2", new BigDecimal("200.0"));
    when(assetPriceRepository.findAll()).thenReturn(List.of(test1, test2));

    seeder(true, TEST_FIXTURE).run(null);

    verifyNoInteractions(marketPriceService);
  }

  // -------------------------------------------------------------------------
  // 6.3 — seeds only the missing ticker when partially seeded
  // -------------------------------------------------------------------------
  @Test
  void run_seedsOnlyMissingTickers_whenPartiallySeeded() {
    var test1 = new AssetPrice("TEST1", new BigDecimal("100.0"));
    when(assetPriceRepository.findAll()).thenReturn(List.of(test1));

    seeder(true, TEST_FIXTURE).run(null);

    verify(marketPriceService, never()).updatePrice(eq("TEST1"), any());
    verify(marketPriceService).updatePrice(eq("TEST2"), any(BigDecimal.class));
    verifyNoMoreInteractions(marketPriceService);
  }

  // -------------------------------------------------------------------------
  // 6.4 — does nothing when disabled
  // -------------------------------------------------------------------------
  @Test
  void run_doesNothing_whenDisabled() {
    seeder(false, TEST_FIXTURE).run(null);

    verifyNoInteractions(assetPriceRepository);
    verifyNoInteractions(marketPriceService);
  }

  // -------------------------------------------------------------------------
  // 6.5 — logs error and continues when fixture file is missing
  // -------------------------------------------------------------------------
  @Test
  void run_logsErrorAndContinues_whenFixtureFileMissing() {
    assertThatNoException().isThrownBy(() -> seeder(true, MISSING_FIXTURE).run(null));

    verifyNoInteractions(assetPriceRepository);
    verifyNoInteractions(marketPriceService);
  }

  // -------------------------------------------------------------------------
  // 6.6 — logs error and continues when fixture JSON is malformed
  // -------------------------------------------------------------------------
  @Test
  void run_logsErrorAndContinues_whenFixtureMalformed() {
    // Write a malformed fixture to a temp location accessible on the test classpath.
    // We use a file: URI pointing to a temp file so we don't need a real classpath resource.
    assertThatNoException()
        .isThrownBy(
            () -> {
              java.io.File tmp = java.io.File.createTempFile("malformed", ".json");
              tmp.deleteOnExit();
              java.nio.file.Files.writeString(tmp.toPath(), "{ this is not valid json }");
              seeder(true, "file:" + tmp.getAbsolutePath()).run(null);
            });

    verifyNoInteractions(assetPriceRepository);
    verifyNoInteractions(marketPriceService);
  }

  // -------------------------------------------------------------------------
  // 6.7 — parses fixture correctly (ticker, basePrice, currency)
  // -------------------------------------------------------------------------
  @Test
  void run_parsesFixtureCorrectly() {
    // Capture the first updatePrice call to verify deserialized values
    when(assetPriceRepository.findAll()).thenReturn(List.of());

    seeder(true, TEST_FIXTURE).run(null);

    verify(marketPriceService).updatePrice("TEST1", new BigDecimal("100.0"));
    verify(marketPriceService).updatePrice("TEST2", new BigDecimal("200.0"));
  }
}
