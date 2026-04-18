package com.wealth.insight;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Property-based tests for MarketDataService pure functions.
 *
 * <p>Property 2: parsePrice never throws for any input.
 * <p>Property 3: calculateTrend correctness for any valid price list.
 */
class MarketDataServicePropertyTest {

    private final MarketDataService service = new MarketDataService(mock(StringRedisTemplate.class));

    // --- Property 2: Price parsing safety — malformed values never throw ---

    @RepeatedTest(100)
    void parsePrice_randomStrings_neverThrows() {
        String input = randomPriceInput();
        assertThatCode(() -> service.parsePrice(input)).doesNotThrowAnyException();
        BigDecimal result = service.parsePrice(input);
        // result is either a valid BigDecimal or null — never an exception
        if (result != null) {
            assertThat(result).isNotNull();
        }
    }

    @Test
    void parsePrice_null_returnsNull() {
        assertThat(service.parsePrice(null)).isNull();
    }

    @Test
    void parsePrice_blank_returnsNull() {
        assertThat(service.parsePrice("   ")).isNull();
    }

    @Test
    void parsePrice_validNumber_returnsBigDecimal() {
        assertThat(service.parsePrice("123.45")).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    void parsePrice_garbage_returnsNull() {
        assertThat(service.parsePrice("not-a-number")).isNull();
    }

    // --- Property 3: Trend calculation correctness ---

    @RepeatedTest(100)
    void calculateTrend_randomValidList_matchesFormula() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int size = rng.nextInt(2, 11);
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            prices.add(BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0)));
        }

        BigDecimal result = MarketDataService.calculateTrend(prices);
        BigDecimal newest = prices.getFirst();
        BigDecimal oldest = prices.getLast();

        if (oldest.compareTo(BigDecimal.ZERO) == 0) {
            assertThat(result).isNull();
        } else {
            BigDecimal expected = newest.subtract(oldest)
                    .divide(oldest, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(result).isEqualByComparingTo(expected);
        }
    }

    @RepeatedTest(100)
    void calculateTrend_singleElement_returnsNull() {
        BigDecimal price = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0.01, 10000.0));
        assertThat(MarketDataService.calculateTrend(List.of(price))).isNull();
    }

    @Test
    void calculateTrend_null_returnsNull() {
        assertThat(MarketDataService.calculateTrend(null)).isNull();
    }

    @Test
    void calculateTrend_empty_returnsNull() {
        assertThat(MarketDataService.calculateTrend(List.of())).isNull();
    }

    @Test
    void calculateTrend_oldestZero_returnsNull() {
        assertThat(MarketDataService.calculateTrend(
                List.of(new BigDecimal("100"), BigDecimal.ZERO))).isNull();
    }

    // --- Helpers ---

    private static String randomPriceInput() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return switch (rng.nextInt(7)) {
            case 0 -> null;
            case 1 -> "";
            case 2 -> "   ";
            case 3 -> String.valueOf(rng.nextDouble(-10000, 10000));
            case 4 -> "abc" + rng.nextInt();
            case 5 -> String.valueOf(rng.nextInt());
            default -> "\u0000\uFFFF\t\n";
        };
    }
}
