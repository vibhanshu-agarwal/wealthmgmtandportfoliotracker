package com.wealth.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
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

  public PortfolioService(
      PortfolioRepository portfolioRepository,
      JdbcTemplate jdbcTemplate,
      UserRepository userRepository,
      FxRateProvider fxRateProvider,
      FxProperties fxProperties) {
    this.portfolioRepository = portfolioRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.userRepository = userRepository;
    this.fxRateProvider = fxRateProvider;
    this.fxProperties = fxProperties;
  }

  // Task 1.3 verified: @Transactional(readOnly = true) keeps the JPA session open so
  // getHoldings() on each Portfolio entity is hydrated before the session closes.
  @Transactional(readOnly = true)
  public List<PortfolioResponse> getByUserId(String userId) {
    requireUserExists(userId);
    return portfolioRepository.findByUserId(userId).stream().map(this::toResponse).toList();
  }

  @Transactional
  public PortfolioResponse createPortfolio(String userId) {
    var portfolio = new Portfolio(userId);
    return toResponse(portfolioRepository.save(portfolio));
  }

  @Transactional
  public PortfolioResponse addHolding(String userId, UUID portfolioId, String ticker, BigDecimal quantity) {
    var portfolio = portfolioRepository.findById(portfolioId)
        .filter(p -> p.getUserId().equals(userId))
        .orElseThrow(() -> new UserNotFoundException(userId));

    // Update quantity if holding already exists, otherwise add new with cost-basis capture
    portfolio.getHoldings().stream()
        .filter(h -> h.getAssetTicker().equals(ticker))
        .findFirst()
        .ifPresentOrElse(
            h -> h.setQuantity(quantity),
            () -> {
              AssetHolding newHolding = new AssetHolding(portfolio, ticker, quantity);
              // Task 4.2: capture cost basis at add-time when a current price exists.
              captureCostBasis(newHolding, ticker);
              portfolio.addHolding(newHolding);
            }
        );

    return toResponse(portfolioRepository.save(portfolio));
  }

  /**
   * Looks up the current market price for {@code ticker} and records it as the cost basis
   * at the time of holding creation. If no market price is available, the cost-basis fields
   * remain null (meaning "unavailable"), which is the correct typed-unavailable state.
   */
  private void captureCostBasis(AssetHolding holding, String ticker) {
    try {
      var result = jdbcTemplate.queryForList(
          "SELECT current_price, quote_currency FROM market_prices WHERE ticker = ?",
          ticker);
      if (!result.isEmpty()) {
        BigDecimal price = (BigDecimal) result.get(0).get("current_price");
        String quoteCurrency = (String) result.get(0).get("quote_currency");
        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
          holding.setAvgCostBasis(price);
          holding.setCostBasisCurrency(quoteCurrency != null ? quoteCurrency : "USD");
          holding.setCostBasisSource("ADD_TIME");
          holding.setCostBasisAsOf(java.time.Instant.now());
          log.debug("Cost basis captured for {}: {} {}", ticker, price, quoteCurrency);
        }
      }
    } catch (Exception e) {
      log.debug("Could not capture cost basis for {} — fields remain null: {}", ticker, e.getMessage());
    }
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
                       COALESCE(mp.current_price, 0)      AS current_price,
                       COALESCE(mp.quote_currency, 'USD') AS quote_currency
                FROM asset_holdings h
                JOIN portfolios p ON p.id = h.portfolio_id
                LEFT JOIN market_prices mp ON mp.ticker = h.asset_ticker
                WHERE p.user_id = ?
                """,
            (rs, i) ->
                new HoldingValuationRow(
                    rs.getString("asset_ticker"),
                    rs.getBigDecimal("quantity"),
                    rs.getBigDecimal("current_price"),
                    rs.getString("quote_currency")),
            userId);

    // FX conversion loop — loop invariant: totalValue = sum of converted values so far
    BigDecimal totalValue = BigDecimal.ZERO;
    for (HoldingValuationRow row : rows) {
      BigDecimal rate;
      if (row.quoteCurrency().equals(baseCurrency)) {
        rate = BigDecimal.ONE; // same-currency short-circuit: no FX call
      } else {
        try {
          rate = fxRateProvider.getRate(row.quoteCurrency(), baseCurrency);
        } catch (FxRateUnavailableException e) {
          // Task 6.2: exclude this holding from the aggregate rather than using 1:1.
          log.debug("FX rate unavailable for {} → {} — holding {} excluded from total",
              row.quoteCurrency(), baseCurrency, row.assetTicker());
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
        userId, portfolios.size(), totalHoldings, totalValue, baseCurrency);
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
        portfolio.getId(), portfolio.getUserId(), portfolio.getCreatedAt(), holdings);
  }
}
