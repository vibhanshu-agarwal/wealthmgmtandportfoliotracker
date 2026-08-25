package com.wealth.portfolio.composition;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single versioned holdings writer (tasks 4.1 / 4.4). D2 order: version → semantic 400 →
 * catalog/lifecycle 422 → materialise → compare → parent CAS → child DML.
 */
@Service
public class HoldingReplacementService {

    static final String UQ_PORTFOLIOS_USER_ID = "uq_portfolios_user_id";

    private final PortfolioRepository portfolioRepository;
    private final CompositionCatalogValidator catalogValidator;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final Clock clock;

    public HoldingReplacementService(
            PortfolioRepository portfolioRepository,
            CompositionCatalogValidator catalogValidator,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            Clock clock) {
        this.portfolioRepository = portfolioRepository;
        this.catalogValidator = catalogValidator;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public CompositionResult replace(
            String userId, long expectedVersion, List<RawIntent> intent, TuplePreparer preparer) {
        List<RawIntent> safeIntent = intent == null ? List.of() : List.copyOf(intent);
        List<Portfolio> existing = portfolioRepository.findByUserId(userId);
        if (existing.isEmpty()) {
            return replaceAbsent(userId, expectedVersion, safeIntent, preparer);
        }
        return replacePresent(existing.getFirst(), expectedVersion, safeIntent, preparer);
    }

    private CompositionResult replaceAbsent(
            String userId, long expectedVersion, List<RawIntent> intent, TuplePreparer preparer) {
        if (expectedVersion != 0L) {
            throw new PortfolioVersionConflictException(0L);
        }
        validateSemantic(intent);
        catalogValidator.validate(intent, List.of());
        List<DesiredHoldingState> desired = preparer.materialise(intent, List.of());

        Portfolio portfolio;
        try {
            portfolio = portfolioRepository.saveAndFlush(new Portfolio(userId));
        } catch (DataIntegrityViolationException e) {
            if (isNamedConstraint(e, UQ_PORTFOLIOS_USER_ID)) {
                throw new PortfolioVersionConflictException();
            }
            throw e;
        }

        // Aggregate_Creation is never a no-op, even with an empty desired set.
        forceParentTransition(portfolio, /* expectedVersion */ 0L);
        applyChildren(portfolio, desired);
        entityManager.flush();
        entityManager.refresh(portfolio);

        return toResult(portfolio, desired, /* created */ true, /* noOp */ false);
    }

    private CompositionResult replacePresent(
            Portfolio portfolio,
            long expectedVersion,
            List<RawIntent> intent,
            TuplePreparer preparer) {
        // Touch the collection while the session is open so the locked snapshot is complete.
        List<HoldingSnapshot> locked = snapshotOf(portfolio.getHoldings());

        if (portfolio.getVersion() != expectedVersion) {
            throw new PortfolioVersionConflictException(portfolio.getVersion());
        }

        validateSemantic(intent);
        catalogValidator.validate(intent, locked);
        List<DesiredHoldingState> desired = preparer.materialise(intent, locked);

        if (tuplesEqual(desired, locked)) {
            return toResult(portfolio, desired, /* created */ false, /* noOp */ true);
        }

        forceParentTransition(portfolio, expectedVersion);
        applyChildren(portfolio, desired);
        entityManager.flush();
        entityManager.refresh(portfolio);

        return toResult(portfolio, desired, /* created */ false, /* noOp */ false);
    }

    private void forceParentTransition(Portfolio portfolio, long expectedVersion) {
        // Bind UTC wall time as LocalDateTime so TIMESTAMP WITHOUT TIME ZONE is not shifted by
        // the JVM default zone (Timestamp.from(Instant) would be).
        LocalDateTime transitionAt =
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE portfolios
                           SET version = version + 1,
                               updated_at = GREATEST(?, updated_at + INTERVAL '1 microsecond')
                         WHERE id = ? AND version = ?
                        """,
                        transitionAt,
                        portfolio.getId(),
                        expectedVersion);
        if (updated != 1) {
            throw new PortfolioVersionConflictException();
        }
        entityManager.refresh(portfolio);
    }

    private void applyChildren(Portfolio portfolio, List<DesiredHoldingState> desired) {
        portfolio.getHoldings().clear();
        // Flush deletes before inserts so UNIQUE(portfolio_id, asset_ticker) is not violated
        // when a retained ticker is rewritten in the same flush cycle.
        entityManager.flush();
        List<AssetHolding> next = new ArrayList<>(desired.size());
        for (DesiredHoldingState d : desired) {
            AssetHolding holding = new AssetHolding(portfolio, d.ticker(), d.quantity());
            holding.setAvgCostBasis(d.avgCostBasis());
            holding.setCostBasisCurrency(d.costBasisCurrency());
            holding.setCostBasisSource(d.costBasisSource());
            holding.setCostBasisAsOf(d.costBasisAsOf());
            next.add(holding);
        }
        for (AssetHolding holding : next) {
            portfolio.addHolding(holding);
        }
    }

    static void validateSemantic(List<RawIntent> intent) {
        List<String> quantityOffenders = new ArrayList<>();
        Set<String> seenQuantity = new LinkedHashSet<>();
        for (RawIntent item : intent) {
            if (!QuantityDomain.isValid(item.quantity()) && seenQuantity.add(item.ticker())) {
                quantityOffenders.add(item.ticker());
            }
        }
        if (!quantityOffenders.isEmpty()) {
            throw new QuantityOutOfDomainException(quantityOffenders);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RawIntent item : intent) {
            counts.merge(item.ticker(), 1, Integer::sum);
        }
        List<String> duplicates =
                counts.entrySet().stream()
                        .filter(e -> e.getValue() > 1)
                        .map(Map.Entry::getKey)
                        .toList();
        if (!duplicates.isEmpty()) {
            throw new DuplicateTickerException(duplicates);
        }
    }

    static boolean tuplesEqual(List<DesiredHoldingState> desired, List<HoldingSnapshot> locked) {
        if (desired.size() != locked.size()) {
            return false;
        }
        Map<String, HoldingSnapshot> byTicker =
                locked.stream()
                        .collect(Collectors.toMap(HoldingSnapshot::ticker, h -> h, (a, b) -> a));
        Set<String> seen = new HashSet<>();
        for (DesiredHoldingState d : desired) {
            if (!seen.add(d.ticker())) {
                return false;
            }
            HoldingSnapshot existing = byTicker.get(d.ticker());
            if (existing == null) {
                return false;
            }
            DesiredHoldingState asDesired =
                    new DesiredHoldingState(
                            existing.ticker(),
                            existing.quantity(),
                            existing.avgCostBasis(),
                            existing.costBasisCurrency(),
                            existing.costBasisSource(),
                            existing.costBasisAsOf());
            if (!d.samePersistedTuple(asDesired)) {
                return false;
            }
        }
        return true;
    }

    static List<HoldingSnapshot> snapshotOf(List<AssetHolding> holdings) {
        return holdings.stream()
                .map(
                        h ->
                                new HoldingSnapshot(
                                        h.getAssetTicker(),
                                        h.getQuantity(),
                                        h.getAvgCostBasis(),
                                        h.getCostBasisCurrency(),
                                        h.getCostBasisSource(),
                                        h.getCostBasisAsOf()))
                .toList();
    }

    /**
     * True only when a structured PostgreSQL/Hibernate constraint name matches {@code name}.
     * Message-substring matching is deliberately rejected so an unrelated integrity fault cannot
     * be misreported as {@code portfolio_version_conflict}.
     */
    static boolean isNamedConstraint(DataIntegrityViolationException e, String name) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve
                    && constraintNameMatches(cve.getConstraintName(), name)) {
                return true;
            }
            if (cause instanceof PSQLException psql) {
                ServerErrorMessage serverError = psql.getServerErrorMessage();
                if (serverError != null
                        && constraintNameMatches(serverError.getConstraint(), name)) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    static boolean constraintNameMatches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        String bare =
                actual.contains(".")
                        ? actual.substring(actual.lastIndexOf('.') + 1)
                        : actual;
        return bare.equalsIgnoreCase(expected);
    }

    private static CompositionResult toResult(
            Portfolio portfolio,
            List<DesiredHoldingState> holdings,
            boolean created,
            boolean noOp) {
        return new CompositionResult(
                portfolio.getId(),
                portfolio.getUserId(),
                portfolio.getCreatedAt(),
                portfolio.getUpdatedAt(),
                portfolio.getVersion(),
                holdings,
                created,
                noOp);
    }
}
