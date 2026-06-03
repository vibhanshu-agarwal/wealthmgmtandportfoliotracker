package com.wealth.market.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the canonical ticker dictionary ({@code seed/seed-tickers.json}) from the classpath at
 * startup. The file is placed on the classpath by the {@code copySeedTickers} Gradle task, which
 * copies {@code config/seed-tickers.json} from the repo root into {@code src/main/resources/seed/}
 * before {@code processResources}.
 *
 * <p>If the file is absent (e.g. in test contexts that do not run copySeedTickers), the registry
 * initialises to an empty state and logs a warning rather than failing the Spring context. Any
 * subsequent call to {@link #all()} will return an empty list, causing the seeder to perform a
 * no-op; integration tests that verify seeding must ensure the file is present.
 */
@Component
public class SeedTickerRegistry {

  private static final Logger log = LoggerFactory.getLogger(SeedTickerRegistry.class);

  /**
   * Represents one entry from {@code seed/seed-tickers.json}.
   *
   * <p>The optional {@code name} and {@code aliases} fields support the Wave-2 catalog enrichment
   * (Task 2 / Req 7.3). Seeding logic uses only the four core fields; these extra fields are
   * intentionally ignored by the seeder.
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is a belt-and-suspenders guard ensuring
   * that future schema additions beyond {@code name}/{@code aliases} do not break deserialization
   * in this service.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SeedTicker(
      String ticker,
      String assetClass,
      String quoteCurrency,
      BigDecimal basePrice,
      String name,
      List<String> aliases) {}
    /**
     * Represents one entry from {@code seed/seed-tickers.json}.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures that future
     * enrichment of the JSON schema (e.g. {@code name}, {@code aliases}) does not
     * break deserialization in this service, which only needs the four core fields
     * for seeding purposes (Req 7.3, 9.5).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedTicker(String ticker, String assetClass, String quoteCurrency, BigDecimal basePrice) {}

  private static final String RESOURCE_PATH = "seed/seed-tickers.json";
  private static final int EXPECTED_TOTAL = 160;

  private List<SeedTicker> tickers = List.of();
  private Map<String, SeedTicker> byTicker = Map.of();

  @PostConstruct
  void load() throws IOException {
    ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
    if (!resource.exists()) {
      log.warn(
          "SeedTickerRegistry: '{}' not found on classpath — registry is empty. "
              + "Run the copySeedTickers Gradle task to populate it.",
          RESOURCE_PATH);
      return;
    }
    ObjectMapper mapper =
        new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    try (InputStream is = resource.getInputStream()) {
      List<SeedTicker> parsed = mapper.readValue(is, new TypeReference<>() {});
      if (parsed.size() != EXPECTED_TOTAL) {
        throw new IllegalStateException(
            "seed-tickers.json must contain exactly "
                + EXPECTED_TOTAL
                + " entries; found "
                + parsed.size());
      }
      validateAssetClassCounts(parsed);
      this.tickers = List.copyOf(parsed);
      this.byTicker =
          parsed.stream().collect(Collectors.toUnmodifiableMap(SeedTicker::ticker, t -> t));
      log.info("SeedTickerRegistry: loaded {} tickers from '{}'", tickers.size(), RESOURCE_PATH);
    }
  }

  private static void validateAssetClassCounts(List<SeedTicker> parsed) {
    Map<String, Long> counts =
        parsed.stream()
            .collect(Collectors.groupingBy(SeedTicker::assetClass, Collectors.counting()));
    requireCount(counts, "US_EQUITY", 50);
    requireCount(counts, "NSE", 50);
    requireCount(counts, "CRYPTO", 50);
    requireCount(counts, "FOREX", 10);
  }

  private static void requireCount(Map<String, Long> counts, String cls, int expected) {
    long actual = counts.getOrDefault(cls, 0L);
    if (actual != expected) {
      throw new IllegalStateException(
          "seed-tickers.json: expected "
              + expected
              + " entries with assetClass="
              + cls
              + ", found "
              + actual);
    }
  }

  public List<SeedTicker> all() {
    return tickers;
  }

  public Optional<SeedTicker> find(String ticker) {
    return Optional.ofNullable(byTicker.get(ticker));
  }
}
