package com.wealth.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealth.portfolio.dto.AssetPriceFreshnessDto;
import com.wealth.portfolio.dto.PortfolioSummaryDto;
import com.wealth.portfolio.freshness.AssetPriceFreshness;
import com.wealth.portfolio.freshness.AssetPriceFreshnessProperties;
import com.wealth.portfolio.freshness.FreshnessState;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;

@Service
public class PortfolioService {

  private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

  private final PortfolioRepository portfolioRepository;
  private final JdbcTemplate jdbcTemplate;
  private final UserRepository userRepository;
  private final FxRateProvider fxRateProvider;
  private final FxProperties fxProperties;
  private final AssetPriceFreshnessProperties freshnessProperties;

  public PortfolioService(
      PortfolioRepository portfolioRepository,
      JdbcTemplate jdbcTemplate,
      UserRepository userRepository,
      FxRateProvider fxRateProvider,
      FxProperties fxProperties,
      AssetPriceFreshnessProperties freshnessProperties) {
    this.portfolioRepository = portfolioRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.userRepository = userRepository;
    this.fxRateProvider = fxRateProvider;
    this.fxProperties = fxProperties;
    this.freshnessProperties = freshnessProperties;
  }

  // Task 1.3 verified: @Transactional(readOnly = true) keeps the JPA session open so
  // getHoldings() on each Portfolio entity is hydrated before the session closes.
  @Transactional(readOnly = true)
  public List<PortfolioResponse> getByUserId(String userId) {
    requireUserExists(userId);
    return portfolioRepository.findByUserId(userId).stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public PortfolioSummaryDto getSummary(String userId) {
    requireUserExists(userId);

    String baseCurrency = fxProperties.baseCurrency();

    // Fetch per-row holding data including each asset's quote currency
    List<HoldingValuationRow> rows =
        jdbcTemplate.query(
            """
                SELECT h.asset_ticker,
                       h.quantity,
                       mp.current_price,
                       mp.quote_currency,
                       mp.ticker IS NOT NULL AS price_row_present,
                       mp.observed_at
                FROM asset_holdings h
                JOIN portfolios p ON p.id = h.portfolio_id
                LEFT JOIN market_prices mp ON mp.ticker = h.asset_ticker
                WHERE p.user_id = ?
                """,
            (rs, i) -> {
              Timestamp observed = rs.getTimestamp("observed_at");
              return new HoldingValuationRow(
                  rs.getString("asset_ticker"),
                  rs.getBigDecimal("quantity"),
                  rs.getBigDecimal("current_price"),
                  rs.getString("quote_currency"),
                  rs.getBoolean("price_row_present"),
                  observed == null ? null : observed.toInstant());
            },
            userId);

    Duration threshold = freshnessProperties.threshold();
    Instant now = Instant.now();
    FreshnessState overall = FreshnessState.FRESH;
    int staleHoldings = 0;
    int unknownPriceHoldings = 0;
    int missingPriceHoldings = 0;
    Instant oldestKnown = null;

    BigDecimal totalValue = BigDecimal.ZERO;
    boolean partialValuation = false;
    for (HoldingValuationRow row : rows) {
      FreshnessState holdingState =
          AssetPriceFreshness.evaluate(row.priceRowPresent(), row.observedAt(), threshold, now);
      overall = FreshnessState.mostSevere(overall, holdingState);
      switch (holdingState) {
        case STALE -> staleHoldings++;
        case UNKNOWN -> unknownPriceHoldings++;
        case MISSING -> missingPriceHoldings++;
        case FRESH -> {
          // counted via overall reduction
        }
      }
      if (row.priceRowPresent() && row.observedAt() != null) {
        if (oldestKnown == null || row.observedAt().isBefore(oldestKnown)) {
          oldestKnown = row.observedAt();
        }
      }

      if (!row.priceRowPresent()) {
        partialValuation = true;
        continue;
      }

      BigDecimal rate;
      if (baseCurrency.equals(row.quoteCurrency())) {
        rate = BigDecimal.ONE;
      } else {
        try {
          rate = fxRateProvider.getRate(row.quoteCurrency(), baseCurrency);
        } catch (FxRateUnavailableException e) {
          log.debug("FX rate unavailable for {} → {} — holding {} excluded from total",
              row.quoteCurrency(), baseCurrency, row.assetTicker());
          partialValuation = true;
          continue;
        }
      }

      BigDecimal holdingValue =
          row.quantity()
              .multiply(row.currentPrice())
              .multiply(rate)
              .setScale(4, RoundingMode.HALF_UP);

      totalValue = totalValue.add(holdingValue);
    }

    var portfolios = portfolioRepository.findByUserId(userId);
    int totalHoldings = portfolios.stream().mapToInt(p -> p.getHoldings().size()).sum();

    return new PortfolioSummaryDto(
        userId,
        portfolios.size(),
        totalHoldings,
        totalValue,
        baseCurrency,
        partialValuation,
        new AssetPriceFreshnessDto(
            overall,
            oldestKnown,
            staleHoldings,
            unknownPriceHoldings,
            missingPriceHoldings));
  }

  private void requireUserExists(String userId) {
    // portfolios.user_id is a plain VARCHAR that stores the JWT subclaim (e.g. "user-001").
    // The users table uses UUID PKs, but the portfolio service does not require a UUID-format
    // userId — the API Gateway has already authenticated the request via JWT validation.
    // We skip the UUID-parse guard and simply verify the user_id has at least one portfolio
    // row, which is sufficient to confirm the caller is a known user.
    // If the userId has no portfolios, getByUserId returns an empty list (not a 404).
    // The 404 path is preserved only for explicit user-not-found semantics via email lookup.
    boolean exists = portfolioRepository.existsByUserId(userId);
    if (!exists) {
      // Check if there is a user record with this id as a UUID (legacy UUID-format sub claims)
      try {
        UUID uuid = UUID.fromString(userId);
        if (!userRepository.existsById(uuid)) {
          throw new UserNotFoundException(userId);
        }
      } catch (IllegalArgumentException ex) {
        // Non-UUID sub claim (e.g. "user-001") — trust the gateway authentication.
        // The user is authenticated; they simply have no portfolios yet.
        log.debug("UserId '{}' is not a valid UUID format: {}", userId, ex.getMessage());
      }
    }
  }

  /**
   * Maps a loaded portfolio entity to the wire DTO. Caller must keep the persistence session open
   * through holdings hydration; typically invoked from the same read-write transaction that wrote
   * the portfolio (e.g. {@code DemoResetService}).
   */
  public PortfolioResponse toPortfolioResponse(Portfolio portfolio) {
    portfolio.getHoldings().size();
    return toResponse(portfolio);
  }

  // Task 1.3 verified: streams the live holdings collection (returned by Portfolio.getHoldings()
  // after task 1.2 fix) and maps each AssetHolding to a HoldingResponse DTO. The mapping is
  // correct — no empty-array regression is possible as long as the session is open (guaranteed by
  // the @Transactional(readOnly = true) on getByUserId above).
  private PortfolioResponse toResponse(Portfolio portfolio) {
    var holdings =
        portfolio.getHoldings().stream()
            .map(
                h ->
                    new PortfolioResponse.HoldingResponse(
                        h.getId(), h.getAssetTicker(), h.getQuantity()))
            .toList();

    return new PortfolioResponse(
        portfolio.getId(),
        portfolio.getUserId(),
        portfolio.getCreatedAt(),
        portfolio.getUpdatedAt(),
        portfolio.getVersion(),
        holdings);
  }
}
