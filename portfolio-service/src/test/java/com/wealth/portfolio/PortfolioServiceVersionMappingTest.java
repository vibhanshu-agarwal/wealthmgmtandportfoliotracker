package com.wealth.portfolio;

import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceVersionMappingTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Mock FxRateProvider fxRateProvider;
    @Mock PortfolioRepository portfolioRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserRepository userRepository;

    PortfolioService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioService(
                portfolioRepository,
                jdbcTemplate,
                userRepository,
                fxRateProvider,
                new FxProperties("USD", null, null, null),
                com.wealth.portfolio.freshness.AssetPriceFreshnessProperties.defaults());
    }

    @Test
    void getByUserIdMapsPersistedNonZeroVersionThroughToResponse() {
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);

        Portfolio portfolio = new Portfolio(USER_ID);
        setField(portfolio, "id", UUID.fromString("11111111-2222-3333-4444-555555555555"));
        setField(portfolio, "version", 5L);
        setField(portfolio, "createdAt", java.time.Instant.parse("2026-08-25T00:00:00Z"));
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of(portfolio));

        List<PortfolioResponse> responses = service.getByUserId(USER_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().version()).isEqualTo(5L);
    }

    @Test
    void getByUserIdMapsPersistedUpdatedAtThroughToResponseUnchanged() {
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);

        java.time.Instant entityUpdatedAt = java.time.Instant.parse("2026-08-27T18:42:09Z");
        Portfolio portfolio = new Portfolio(USER_ID);
        setField(portfolio, "id", UUID.fromString("11111111-2222-3333-4444-555555555555"));
        setField(portfolio, "version", 5L);
        setField(portfolio, "createdAt", java.time.Instant.parse("2026-08-25T00:00:00Z"));
        setField(portfolio, "updatedAt", entityUpdatedAt);
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of(portfolio));

        List<PortfolioResponse> responses = service.getByUserId(USER_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().updatedAt()).isEqualTo(entityUpdatedAt);
        assertThat(responses.getFirst().updatedAt()).isNotEqualTo(responses.getFirst().createdAt());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
