# Implementation Plan: Market Data Seeder — Dynamic Configurable Fixture Profiles

## Overview

Replace the hardcoded `Map<String, BigDecimal>` in `LocalMarketDataSeeder` with an externalized
JSON fixture loaded via Jackson `ObjectMapper` and `ResourceLoader`. Add typed configuration
properties, new JSON-mapped records, fixture files, and full unit + integration test coverage.
The seeder must be resilient: fixture errors are logged and swallowed — they never abort startup.

## Tasks

- [x] 1. Create data model records and configuration properties
  - Create `MarketSeedProperties` record in `com.wealth.market` with `@ConfigurationProperties(prefix = "market.seed")`, fields `boolean enabled` and `String fixturePath`
  - Create `SeedAsset` record in `com.wealth.market` with fields `String ticker`, `BigDecimal basePrice`, `String currency`
  - Create `MarketSeedFixture` record in `com.wealth.market` with field `List<SeedAsset> assets`
  - Add `@EnableConfigurationProperties(MarketSeedProperties.class)` to `MarketDataApplication`
  - _Requirements: 1.1, 1.2, 1.3, 2.1_

- [x] 2. Create fixture JSON files
  - Create `market-data-service/src/main/resources/fixtures/market-seed-data.json` with 6 assets: AAPL (212.5), TSLA (276.0), BTC (70775.0), MSFT (425.3), NVDA (938.6), ETH (3540.5) — all USD
  - Create `market-data-service/src/test/resources/fixtures/test-market-seed-data.json` with 2 synthetic assets: TEST1 (100.0 USD), TEST2 (200.0 USD)
  - _Requirements: 1.4, 3.1_

- [x] 3. Update application configuration
  - In `application.yml`: add `market.seed.fixture-path: classpath:fixtures/market-seed-data.json` under the existing `market.seed` block
  - Create `market-data-service/src/main/resources/application-aws.yml` with `market.seed.enabled: false`
  - _Requirements: 2.2, 2.3_

- [x] 4. Refactor `LocalMarketDataSeeder` to load fixture via JSON
  - Inject `ObjectMapper`, `MarketSeedProperties`, and `ResourceLoader` (remove `@Value` field and hardcoded map)
  - In `run()`: check `props.enabled()`, short-circuit with info log if false
  - Resolve fixture resource via `resourceLoader.getResource(props.fixturePath())`
  - Wrap `objectMapper.readValue(resource.getInputStream(), MarketSeedFixture.class)` and the resource resolution in a try-catch covering `FileNotFoundException`, `IllegalArgumentException`, and `JsonProcessingException`; on catch: log at ERROR level with detail and return — do not rethrow
  - Snapshot existing tickers once via `assetPriceRepository.findAll()`, then loop and call `marketPriceService.updatePrice` only for absent tickers
  - Log summary: "Seeded N missing market prices into MongoDB" or "Seed skipped: all baseline tickers already present"
  - Configure `ObjectMapper` bean (or rely on Spring Boot auto-configured one) with `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS` enabled
  - _Requirements: 1.5, 2.1, 2.4, 3.2_

- [x] 5. Checkpoint — verify compilation
  - Ensure all modules compile cleanly: `./gradlew :market-data-service:compileJava :market-data-service:compileTestJava`
  - Ask the user if any questions arise before proceeding to tests.

- [x] 6. Write unit tests for `LocalMarketDataSeeder`
  - Create `market-data-service/src/test/java/com/wealth/market/LocalMarketDataSeederTest.java`
  - Use Mockito for `AssetPriceRepository` and `MarketPriceService`; use a real `ObjectMapper` configured with `USE_BIG_DECIMAL_FOR_FLOATS` and `findAndRegisterModules()`; load `test-market-seed-data.json` from test classpath via `ResourceLoader`
  - Implement the following test methods:
    - [x] 6.1 `run_seedsAllAssets_whenDatabaseIsEmpty` — repo returns empty list; assert `updatePrice` called once per asset in test fixture
    - [x] 6.2 `run_skipsExistingTickers_whenAlreadyPresent` — repo returns both TEST1 and TEST2; assert `updatePrice` never called
    - [x] 6.3 `run_seedsOnlyMissingTickers_whenPartiallySeeded` — repo returns TEST1 only; assert `updatePrice` called exactly once for TEST2
    - [x] 6.4 `run_doesNothing_whenDisabled` — `props.enabled=false`; assert zero interactions with repo and service
    - [x] 6.5 `run_logsErrorAndContinues_whenFixtureFileMissing` — fixture path points to non-existent resource; assert no exception thrown and `updatePrice` never called
    - [x] 6.6 `run_logsErrorAndContinues_whenFixtureMalformed` — fixture resource contains invalid JSON; assert no exception thrown and `updatePrice` never called
    - [x] 6.7 `run_parsesFixtureCorrectly` — assert deserialized `SeedAsset` has correct `ticker`, `basePrice`, and `currency` values matching test fixture
  - _Requirements: 1.5, 2.1, 2.4, 3.2_

- [x] 7. Write integration tests for `LocalMarketDataSeeder`
  - Create `market-data-service/src/test/java/com/wealth/market/LocalMarketDataSeederIntegrationTest.java`
  - Annotate with `@Tag("integration")`, `@SpringBootTest`, `@ActiveProfiles("local")`, Testcontainers MongoDB, and `@MockBean KafkaTemplate`
  - Implement the following test methods:
    - [x] 7.1 `contextLoads_andSeedsFixture` — after context startup, assert `assetPriceRepository.findAll()` contains all 6 tickers from the main fixture
    - [x] 7.2 `seeder_isIdempotent` — call `ApplicationRunner.run(null)` a second time; assert document count in MongoDB does not increase
    - [x] 7.3 `seeder_doesNotActivate_underAwsProfile` — `@ActiveProfiles("aws")`; assert `LocalMarketDataSeeder` bean is absent from the application context
  - _Requirements: 2.3, 3.2_

- [x] 8. Final checkpoint — run full test suite
  - Ensure all tests pass, ask the user if questions arise.
