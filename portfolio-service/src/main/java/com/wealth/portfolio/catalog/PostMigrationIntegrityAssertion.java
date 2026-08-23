package com.wealth.portfolio.catalog;

import com.wealth.catalog.SupportedCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup-blocking gate for Spec A task 6.7. Runs after Flyway, before the
 * application serves traffic. Failure is fatal — the same posture as {@code
 * catalog_load_failed}.
 */
@Component
@DependsOn("flywayInitializer")
public class PostMigrationIntegrityAssertion {

    private static final Logger log = LoggerFactory.getLogger(PostMigrationIntegrityAssertion.class);

    public PostMigrationIntegrityAssertion(JdbcTemplate jdbcTemplate, SupportedCatalog catalog) {
        assertSatisfied(jdbcTemplate, catalog);
    }

    public static void assertSatisfied(JdbcTemplate jdbcTemplate, SupportedCatalog catalog) {
        List<String> violations = evaluate(jdbcTemplate, catalog);
        if (!violations.isEmpty()) {
            log.error(
                    "post_migration_integrity_failed resource={} service={} violations={}",
                    "repair_audit/asset_holdings",
                    "portfolio-service",
                    violations);
            throw new PostMigrationIntegrityFailedException(violations);
        }
    }

    public static List<String> evaluate(JdbcTemplate jdbcTemplate, SupportedCatalog catalog) {
        List<String> violations = new ArrayList<>();
        violations.addAll(assertAuditTickersAreActive(jdbcTemplate, catalog));
        violations.addAll(assertReferentialInvariant(jdbcTemplate, catalog));
        violations.addAll(assertV18Postconditions(jdbcTemplate));
        violations.addAll(assertV19Postconditions(jdbcTemplate));
        return List.copyOf(violations);
    }

    static List<String> assertAuditTickersAreActive(JdbcTemplate jdbc, SupportedCatalog catalog) {
        List<String> violations = new ArrayList<>();
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT DISTINCT asset_ticker
                        FROM repair_audit
                        ORDER BY asset_ticker
                        """);
        for (Map<String, Object> row : rows) {
            String ticker = (String) row.get("asset_ticker");
            if (!catalog.isActive(ticker)) {
                violations.add(
                        "repair_audit ticker is not ACTIVE: "
                                + ticker
                                + " (migration-created/replaced holdings must name an Active_Asset)");
            }
        }
        return violations;
    }

    static List<String> assertReferentialInvariant(JdbcTemplate jdbc, SupportedCatalog catalog) {
        List<String> violations = new ArrayList<>();
        List<String> tickers =
                jdbc.queryForList(
                        """
                        SELECT DISTINCT asset_ticker
                        FROM asset_holdings
                        ORDER BY asset_ticker
                        """,
                        String.class);
        for (String ticker : tickers) {
            if (catalog.find(ticker).isEmpty()) {
                violations.add(
                        "asset_holdings ticker is not in the catalog: "
                                + ticker
                                + " (Referential_Invariant)");
            }
        }
        return violations;
    }

    static List<String> assertV18Postconditions(JdbcTemplate jdbc) {
        List<String> violations = new ArrayList<>();
        addIfPositive(
                violations,
                count(jdbc, "SELECT COUNT(*) FROM asset_holdings WHERE asset_ticker = 'BTC'"),
                "V18: asset_holdings still contains BTC");
        addIfPositive(
                violations,
                count(jdbc, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'BTC'"),
                "V18: market_prices still contains BTC");
        addIfPositive(
                violations,
                count(jdbc, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'BTC'"),
                "V18: market_price_history still contains operational BTC rows");
        return violations;
    }

    static List<String> assertV19Postconditions(JdbcTemplate jdbc) {
        List<String> violations = new ArrayList<>();
        addIfPositive(
                violations,
                count(jdbc, "SELECT COUNT(*) FROM asset_holdings WHERE asset_ticker = 'MM.NS'"),
                "V19: asset_holdings still contains MM.NS");
        addIfPositive(
                violations,
                count(jdbc, "SELECT COUNT(*) FROM market_prices WHERE ticker = 'MM.NS'"),
                "V19: market_prices still contains MM.NS");
        addIfPositive(
                violations,
                count(jdbc, "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'MM.NS'"),
                "V19: market_price_history still contains MM.NS");
        return violations;
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static void addIfPositive(List<String> violations, int n, String message) {
        if (n > 0) {
            violations.add(message + " (" + n + ")");
        }
    }
}
