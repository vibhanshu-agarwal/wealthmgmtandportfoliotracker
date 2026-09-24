package com.wealth.portfolio;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC conversions for the market-price observation columns ({@code market_prices.observed_at},
 * {@code market_price_history.observed_at}), which are {@code TIMESTAMP} <em>without</em> a time
 * zone and hold UTC wall-clock time.
 *
 * <p>A {@link java.sql.Timestamp} is rendered and parsed by the driver in the JVM's default zone,
 * so binding or reading one makes the stored value depend on where the service runs (Gradle runs
 * tests and {@code bootRun} with {@code -Duser.timezone=Asia/Kolkata}). {@link LocalDateTime} is
 * passed through unchanged, so converting at UTC here keeps every read and write zone-independent.
 * SQL that compares these columns with {@code now()} must likewise use
 * {@code now() AT TIME ZONE 'UTC'}, never the session zone.
 */
final class UtcTimestamps {

    private UtcTimestamps() {}

    /** The UTC wall-clock value to bind for {@code instant}; null stays null. */
    static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Reads a UTC wall-clock column back as the instant it represents; SQL NULL → null. */
    static Instant readUtc(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
