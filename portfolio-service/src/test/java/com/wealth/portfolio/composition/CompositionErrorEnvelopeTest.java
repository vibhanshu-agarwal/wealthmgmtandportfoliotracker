package com.wealth.portfolio.composition;

import com.wealth.catalog.UnsupportedAssetException;
import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositionErrorEnvelopeTest {

    @Mock
    PortfolioRepository portfolioRepository;

    @Test
    void versionConflictMapsTo409WithCurrentVersion() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);

        ResponseEntity<ContractError> response =
                handler.handlePortfolioVersionConflict(new PortfolioVersionConflictException(7L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(body -> {
                    assertThat(body.error()).isEqualTo(ContractErrorCode.portfolio_version_conflict);
                    assertThat(body.currentVersion()).isEqualTo(7L);
                });
    }

    @Test
    void uniquenessRaceConflictReReadsCurrentVersionByUserId() {
        String userId = "user-race";
        Portfolio winner = new Portfolio(userId);
        setField(winner, "version", 3L);
        when(portfolioRepository.findByUserId(userId)).thenReturn(List.of(winner));
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);

        ResponseEntity<ContractError> response =
                handler.handlePortfolioVersionConflict(
                        PortfolioVersionConflictException.unresolvedForUser(userId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().currentVersion()).isEqualTo(3L);
        assertThat(response.getBody().error()).isEqualTo(ContractErrorCode.portfolio_version_conflict);
    }

    @Test
    void failedCasConflictReReadsCurrentVersionByPortfolioId() {
        UUID portfolioId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Portfolio current = new Portfolio("user-cas");
        setField(current, "version", 11L);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(current));
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);

        ResponseEntity<ContractError> response =
                handler.handlePortfolioVersionConflict(
                        PortfolioVersionConflictException.unresolvedForPortfolio(portfolioId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().currentVersion()).isEqualTo(11L);
    }

    @Test
    void quantityOutOfDomainMapsTo400WithTickers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);
        ResponseEntity<ContractError> response =
                handler.handleQuantityOutOfDomain(
                        new QuantityOutOfDomainException(List.of("AAPL", "MSFT")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(body -> {
                    assertThat(body.error()).isEqualTo(ContractErrorCode.quantity_out_of_domain);
                    assertThat(body.tickers()).containsExactly("AAPL", "MSFT");
                });
    }

    @Test
    void duplicateTickerMapsTo400WithTickers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);
        ResponseEntity<ContractError> response =
                handler.handleDuplicateTicker(new DuplicateTickerException(List.of("AAPL")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo(ContractErrorCode.duplicate_ticker);
        assertThat(response.getBody().tickers()).containsExactly("AAPL");
    }

    @Test
    void pluralUnsupportedAssetsMapsTo422WithTickerAndOrderedTickers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);
        ResponseEntity<ContractError> response =
                handler.handleUnsupportedAssets(
                        new UnsupportedAssetsException(List.of("FOO", "BAR"), "cat-v1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(body -> {
                    assertThat(body.error()).isEqualTo(ContractErrorCode.unsupported_asset);
                    assertThat(body.catalogVersion()).isEqualTo("cat-v1");
                    assertThat(body.ticker()).isEqualTo("FOO");
                    assertThat(body.tickers()).containsExactly("FOO", "BAR");
                });
    }

    @Test
    void lifecycleNotPermittedMapsTo422WithTickerAndTickers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);
        ResponseEntity<ContractError> response =
                handler.handleLifecycleNotPermitted(
                        new LifecycleNotPermittedException(List.of("TATAMOTORS.NS"), "cat-v1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(body -> {
                    assertThat(body.error()).isEqualTo(ContractErrorCode.lifecycle_not_permitted);
                    assertThat(body.ticker()).isEqualTo("TATAMOTORS.NS");
                    assertThat(body.tickers()).containsExactly("TATAMOTORS.NS");
                    assertThat(body.catalogVersion()).isEqualTo("cat-v1");
                });
    }

    @Test
    void singularSpecAUnsupportedAssetBodyPreservedByteForByte() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(portfolioRepository);
        UnsupportedAssetException ex = new UnsupportedAssetException("FAKE", "c3dcb95e4e09212a");

        ResponseEntity<Map<String, Object>> response = handler.handleUnsupportedAsset(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .containsOnlyKeys("error", "ticker", "catalogVersion")
                .containsEntry("error", "unsupported_asset")
                .containsEntry("ticker", "FAKE")
                .containsEntry("catalogVersion", "c3dcb95e4e09212a");
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
